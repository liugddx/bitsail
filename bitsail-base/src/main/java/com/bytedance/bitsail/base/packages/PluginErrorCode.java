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

package com.bytedance.bitsail.base.packages;

import com.bytedance.bitsail.common.exception.ErrorCode;

public enum PluginErrorCode implements ErrorCode {
  PLUGIN_COMMON_ERROR("Plugin-Error-01", "Plugin explorer common error."),
  PLUGIN_REGISTER_ERROR("Plugin-Error-02", "Register plugin to execution failed."),
  PLUGIN_NOT_FOUND_ERROR("Plugin-Error-03", "Plugin not found."),
  PLUGIN_NEW_INSTANCE_ERROR("Plugin-Error-04", "Plugin new instance failed."),
  PLUGIN_FILE_NOT_FOUND_ERROR("Plugin-Error-05", "Plugin library not exists.");

  private final String code;
  private final String description;

  PluginErrorCode(String code, String description) {
    this.code = code;
    this.description = description;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public String getDescription() {
    return description;
  }
}
