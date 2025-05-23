/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.model.schema;

/**
 * The type of transformation to apply to a field in the schema to generate the partition value.
 * YEAR, MONTH, DAY, HOUR applies to source field which is of timestamp type whereas VALUE type
 * represents identity transformation.
 *
 * @since 0.1
 */
public enum PartitionTransformType {
  YEAR,
  MONTH,
  DAY,
  HOUR,
  VALUE,
  BUCKET;

  public boolean isTimeBased() {
    return this == YEAR || this == MONTH || this == DAY || this == HOUR;
  }
}
