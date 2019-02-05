/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard.model

enum InstanceType {

    T1Micro("t1.micro"),
    T2Nano("t2.nano"),
    T2Micro("t2.micro"),
    T2Small("t2.small"),
    T2Medium("t2.medium"),
    T2Large("t2.large"),
    T3Nano("t3.nano"),
    T3Micro("t3.micro"),
    T3Small("t3.small"),
    T3Medium("t3.medium"),
    T3Large("t3.large"),
    T3xLarge("t3.xlarge"),
    T32xLarge("t3.2xlarge"),
    T3ANano("t3a.nano"),
    T3AMicro("t3a.micro"),
    T3ASmall("t3a.small"),
    T3AMedium("t3a.medium"),
    T3ALarge("t3a.large"),
    T3AxLarge("t3a.xlarge"),
    T3A2xLarge("t3a.2xlarge"),

    M4Large("m4.large"),
    M4Xlarge("m4.xlarge"),
    M42xlarge("m4.2xlarge"),
    M44xlarge("m4.4xlarge"),
    M410xlarge("m4.10xlarge"),
    M5Large("m5.large"),
    M5Xlarge("m5.xlarge"),
    M52xlarge("m5.2xlarge"),
    M54xlarge("m5.4xlarge"),
    M512xlarge("m5.12xlarge"),
    M524xlarge("m5.24xlarge"),

    C4Large("c4.large"),
    C4Xlarge("c4.xlarge"),
    C42xlarge("c4.2xlarge"),
    C44xlarge("c4.4xlarge"),
    C48xlarge("c4.8xlarge"),
    C5Large("c5.large"),
    C5Xlarge("c5.xlarge"),
    C52xlarge("c5.2xlarge"),
    C54xlarge("c5.4xlarge"),
    C59xlarge("c5.9xlarge"),
    C518xlarge("c5.18xlarge"),
    C5DLarge("c5d.large"),
    C5DXlarge("c5d.xlarge"),
    C5D2xlarge("c5d.2xlarge"),
    C5D4xlarge("c5d.4xlarge"),
    C5D9xlarge("c5d.9xlarge"),
    C5D18xlarge("c5d.18xlarge"),

    Cr18xlarge("cr1.8xlarge"),
    I2Xlarge("i2.xlarge"),
    I22xlarge("i2.2xlarge"),
    I24xlarge("i2.4xlarge"),
    I28xlarge("i2.8xlarge"),
    Hi14xlarge("hi1.4xlarge"),
    Hs18xlarge("hs1.8xlarge"),
    G22xlarge("g2.2xlarge"),
    Cg14xlarge("cg1.4xlarge"),
    R3Large("r3.large"),
    R3Xlarge("r3.xlarge"),
    R32xlarge("r3.2xlarge"),
    R34xlarge("r3.4xlarge"),
    R38xlarge("r3.8xlarge")

    private String value

    InstanceType(String value) {
        this.value = value
    }

    @Override
    String toString() {
        this.value
    }

    /**
     * @param value as String (i.e. t1.micro, etc)
     * @return InstanceType enum
     */
    static InstanceType fromValue(String value) {
        try {
            if (value == null || "" == value) {
                throw new IllegalArgumentException("Value cannot be null or empty!")
            }
            return InstanceType."${value.tokenize('.')*.capitalize().join()}"
        } catch (ex) {
            throw new IllegalArgumentException("Cannot create enum from ${value}")
        }
    }
}
