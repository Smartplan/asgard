/*
 * Copyright 2013 Netflix, Inc.
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

import com.amazonaws.services.ec2.model.Image
import com.netflix.asgard.AppRegistration
import com.netflix.asgard.UserContext
import groovy.transform.Canonical

/**
 * Container for things that could be useful to know about the context of a deployment.
 */
@Canonical class LaunchContext {

    /**
     * Info about the desired region, who is performing the deployment, and why.
     */
    UserContext userContext

    /**
     * The image being deployed.
     */
    Image image

    /**
     * The registered application being deployed.
     */
    AppRegistration application

    /**
     * An object similar in most ways to the auto scaling group in which the deployment will occur.
     */
    AutoScalingGroupBeanOptions autoScalingGroup

    /**
     * An object similar in most ways to the launch configuration with which the deployment will be done.
     */
    LaunchConfigurationBeanOptions launchConfiguration
}
