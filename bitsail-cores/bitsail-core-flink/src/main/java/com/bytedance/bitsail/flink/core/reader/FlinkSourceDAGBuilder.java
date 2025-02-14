/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.bytedance.bitsail.flink.core.reader;

import com.bytedance.bitsail.base.connector.reader.DataReaderDAGBuilder;
import com.bytedance.bitsail.base.connector.reader.v1.Source;
import com.bytedance.bitsail.base.connector.reader.v1.SourceSplit;
import com.bytedance.bitsail.base.dirty.AbstractDirtyCollector;
import com.bytedance.bitsail.base.dirty.DirtyCollectorFactory;
import com.bytedance.bitsail.base.execution.ExecutionEnviron;
import com.bytedance.bitsail.base.execution.ProcessResult;
import com.bytedance.bitsail.base.extension.GlobalCommittable;
import com.bytedance.bitsail.base.extension.ParallelismComputable;
import com.bytedance.bitsail.base.extension.TypeInfoConverterFactory;
import com.bytedance.bitsail.base.messenger.Messenger;
import com.bytedance.bitsail.base.messenger.MessengerFactory;
import com.bytedance.bitsail.base.messenger.checker.DirtyRecordChecker;
import com.bytedance.bitsail.base.messenger.common.MessengerGroup;
import com.bytedance.bitsail.base.messenger.context.MessengerContext;
import com.bytedance.bitsail.base.messenger.context.SimpleMessengerContext;
import com.bytedance.bitsail.base.parallelism.ParallelismAdvice;
import com.bytedance.bitsail.common.BitSailException;
import com.bytedance.bitsail.common.configuration.BitSailConfiguration;
import com.bytedance.bitsail.common.exception.CommonErrorCode;
import com.bytedance.bitsail.common.option.CommonOptions;
import com.bytedance.bitsail.common.type.TypeInfoConverter;
import com.bytedance.bitsail.flink.core.delagate.reader.source.DelegateFlinkSource;
import com.bytedance.bitsail.flink.core.delagate.reader.source.operator.DelegateSourceOperatorFactory;
import com.bytedance.bitsail.flink.core.execution.FlinkExecutionEnviron;
import com.bytedance.bitsail.flink.core.plugins.InputAdapter;
import com.bytedance.bitsail.flink.core.util.AccumulatorRestorer;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class FlinkSourceDAGBuilder<T, SplitT extends SourceSplit, StateT extends Serializable>
    implements DataReaderDAGBuilder, ParallelismComputable, GlobalCommittable, TypeInfoConverterFactory {
  private static final Logger LOG = LoggerFactory.getLogger(FlinkSourceDAGBuilder.class);

  private final Source<T, SplitT, StateT> source;

  private MessengerContext messengerContext;

  private DelegateFlinkSource<T, SplitT, StateT> delegateFlinkSource;

  private AbstractDirtyCollector dirtyCollector;

  private DirtyRecordChecker dirtyRecordChecker;

  private Messenger messenger;

  public FlinkSourceDAGBuilder(Source<T, SplitT, StateT> source) {
    this.source = source;
  }

  @Override
  public void configure(ExecutionEnviron execution, BitSailConfiguration readerConfiguration) throws Exception {
    this.source.configure(execution, readerConfiguration);
    this.messengerContext = SimpleMessengerContext.builder()
        .messengerGroup(MessengerGroup.READER)
        .instanceId(execution.getCommonConfiguration().get(CommonOptions.INTERNAL_INSTANCE_ID))
        .build();
    this.dirtyCollector = DirtyCollectorFactory.initDirtyCollector(execution.getCommonConfiguration(),
        messengerContext);
    this.messenger = MessengerFactory.initMessenger(execution.getCommonConfiguration(),
        messengerContext);
    this.dirtyRecordChecker = new DirtyRecordChecker(execution.getCommonConfiguration());
    this.delegateFlinkSource = new DelegateFlinkSource<>(source,
        execution.getCommonConfiguration(),
        readerConfiguration,
        dirtyCollector,
        messenger);
  }

  public DataStream<T> fromSource(FlinkExecutionEnviron executionEnviron,
                                  int readerParallelism) throws Exception {
    DataStreamSource<T> dataStreamSource = new DataStreamSource<>(
        getStreamOperator(executionEnviron, readerParallelism)
    );

    setDataSourceParallel(dataStreamSource);
    SingleOutputStreamOperator<T> dataStream = dataStreamSource.setParallelism(readerParallelism)
        .uid(source.getReaderName());

    //todo remove in future
    TypeInformation<T> typeInformation = dataStream.getType();
    InputAdapter inputAdapter = new InputAdapter();
    inputAdapter.initFromConf(executionEnviron.getCommonConfiguration(), BitSailConfiguration.newDefault(), (RowTypeInfo) typeInformation);
    dataStream = dataStream
        .flatMap((FlatMapFunction) inputAdapter)
        .name(inputAdapter.getType())
        .setParallelism(dataStream.getParallelism());

    return dataStream;
  }

  @SuppressWarnings("unchecked")
  private SingleOutputStreamOperator<T> getStreamOperator(FlinkExecutionEnviron flinkExecutionEnviron,
                                                          int readerParallelism)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
    Class<SingleOutputStreamOperator> clazz = SingleOutputStreamOperator.class;
    Constructor<SingleOutputStreamOperator> constructor = clazz.getDeclaredConstructor(
        StreamExecutionEnvironment.class,
        Transformation.class);
    constructor.setAccessible(true);
    return constructor.<SingleOutputStreamOperator<T>>newInstance(
        flinkExecutionEnviron.getExecutionEnvironment(),
        new SourceTransformation<T>(source.getReaderName(),
            new DelegateSourceOperatorFactory<>(delegateFlinkSource, WatermarkStrategy.noWatermarks()),
            delegateFlinkSource.getProducedType(),
            readerParallelism)
    );
  }

  private static <T> void setDataSourceParallel(DataStreamSource<T> dataStreamSource) {
    Field field = FieldUtils.getDeclaredField(dataStreamSource.getClass(), "isParallel", true);
    try {
      field.set(dataStreamSource, true);
    } catch (Exception e) {
      throw BitSailException.asBitSailException(CommonErrorCode.RUNTIME_ERROR, e);
    }
  }

  @Override
  public String getReaderName() {
    return source.getReaderName();
  }

  @Override
  public ParallelismAdvice getParallelismAdvice(BitSailConfiguration commonConf,
                                                BitSailConfiguration readerConfiguration,
                                                ParallelismAdvice upstreamAdvice) throws Exception {
    if (source instanceof ParallelismComputable) {
      return ((ParallelismComputable) source)
          .getParallelismAdvice(commonConf, readerConfiguration, upstreamAdvice);
    }
    //todo
    return null;
  }

  @Override
  public void commit(ProcessResult<?> processResult) throws Exception {
    AccumulatorRestorer.restoreAccumulator((ProcessResult<JobExecutionResult>) processResult,
        messengerContext);
    dirtyCollector.restoreDirtyRecords(processResult);
    LOG.info("Checking dirty records during output...");
    dirtyRecordChecker.check(processResult, MessengerGroup.READER);
  }

  @Override
  public void abort() throws Exception {

  }

  @Override
  public TypeInfoConverter createTypeInfoConverter() {
    return source.createTypeInfoConverter();
  }
}
