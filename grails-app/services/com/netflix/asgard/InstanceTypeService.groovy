/*
 * Copyright 2012 Netflix, Inc.
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
package com.netflix.asgard

import com.netflix.asgard.model.InstanceType
import com.google.common.collect.ArrayTable
import com.google.common.collect.Table
import com.netflix.asgard.cache.CacheInitializer
import com.netflix.asgard.mock.MockFileUtils
import com.netflix.asgard.model.HardwareProfile
import com.netflix.asgard.model.InstancePriceType
import com.netflix.asgard.model.InstanceProductType
import com.netflix.asgard.model.InstanceTypeData
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONElement

/**
 * Scrapes web pages and json feeds from Amazon to parse technical and financial information about instance types.
 */
class InstanceTypeService implements CacheInitializer {

    static transactional = false

    final BigDecimal lowPrioritySpotPriceFactor = 1.0

    def grailsApplication
    def awsEc2Service
    Caches caches
    def configService
    def emailerService
    def restClientService

    void initializeCaches() {
        // Use one thread for all these data sources. None of these need updating more than once an hour.
        caches.allOnDemandPrices.ensureSetUp({ retrieveInstanceTypeOnDemandPricing() })
        caches.allInstanceTypes.ensureSetUp({ Region region -> buildInstanceTypes(region) })
        caches.allHardwareProfiles.ensureSetUp({ retrieveHardwareProfiles() }, {
            caches.allOnDemandPrices.fill()
            caches.allInstanceTypes.fill()
        })
    }

    /** Costs less, can take longer to start, can terminate sooner */
    BigDecimal calculateLeisureLinuxSpotBid(UserContext userContext, String instanceTypeName) {
        InstanceTypeData instanceType = getInstanceType(userContext, instanceTypeName)
        instanceType.linuxOnDemandPrice * lowPrioritySpotPriceFactor
    }

    InstanceTypeData getInstanceType(UserContext userContext, String instanceTypeName) {
        caches.allInstanceTypes.by(userContext.region).get(instanceTypeName)
    }

    /**
     * Gets the instance types with associated pricing data for the current region.
     *
     * @param userContext who, where, why
     * @return the instance types, sorted by price, with unpriced types at the end sorted by name
     */
    Collection<InstanceTypeData> getInstanceTypes(UserContext userContext) {
        caches.allInstanceTypes.by(userContext.region).list().sort { a, b ->
            BigDecimal aPrice = a.linuxOnDemandPrice
            BigDecimal bPrice = b.linuxOnDemandPrice
            if (aPrice == null) {
                return bPrice == null ? a.name <=> b.name : 1 // b goes first when a is the only null price
            } else if (bPrice == null) {
                return -1 // a goes first when b is the only null price
            }
            // When both prices exist, smaller goes first. Return integers. Avoid subtraction decimal rounding errors.
            return aPrice < bPrice ? -1 : 1
        }
    }

    private JSONElement fetchPricingJsonData(InstancePriceType instancePriceType) {
        Boolean online = grailsApplication.config.server.online
        String pricingJsonUrl = instancePriceType.url
        String fileName = instancePriceType.dataSourceFileName
        online ? restClientService.getAsJson(pricingJsonUrl) : MockFileUtils.parseJsonFile(fileName)
    }

    private Collection<HardwareProfile> getHardwareProfiles() {
        caches.allHardwareProfiles.list()
    }

    private RegionalInstancePrices getOnDemandPrices(Region region) {
        caches.allOnDemandPrices.by(region)
    }

    private List<InstanceTypeData> buildInstanceTypes(Region region) {

        Map<String, InstanceTypeData> namesToInstanceTypeDatas = [:]
        Set<InstanceType> enumInstanceTypes = InstanceType.values() as Set

        // Compile standard instance types, first without optional hardware and pricing metadata.
        for (InstanceType instanceType in enumInstanceTypes) {
            String name = instanceType.toString()
            namesToInstanceTypeDatas[name] = new InstanceTypeData(
                    hardwareProfile: new HardwareProfile(instanceType: name)
            )
        }

        // Add any custom instance types that are still missing from the InstanceType enum.
        Collection<InstanceTypeData> customInstanceTypes = configService.customInstanceTypes
        for (InstanceTypeData customInstanceTypeData in customInstanceTypes) {
            String name = customInstanceTypeData.name
            namesToInstanceTypeDatas[name] = customInstanceTypeData
        }

        Collection<HardwareProfile> hardwareProfiles = getHardwareProfiles()
        RegionalInstancePrices onDemandPrices = getOnDemandPrices(region)
        for (InstanceType instanceType in enumInstanceTypes) {
            String name = instanceType.toString()
            HardwareProfile hardwareProfile = hardwareProfiles.find { it.instanceType == name }
            if (hardwareProfile) {
                InstanceTypeData instanceTypeData = new InstanceTypeData(
                        hardwareProfile: hardwareProfile,
                        linuxOnDemandPrice: onDemandPrices?.get(instanceType, InstanceProductType.LINUX_UNIX),
                )
                namesToInstanceTypeDatas[name] = instanceTypeData
            } else {
                log.info "Unable to resolve ${instanceType}"
            }
        }
        // Sort based on Linux price if possible. Otherwise sort by name.
        List<InstanceTypeData> instanceTypeDatas = namesToInstanceTypeDatas.values() as List
        instanceTypeDatas.sort { a, b -> a.name <=> b.name }
        instanceTypeDatas.sort { a, b -> a.linuxOnDemandPrice <=> b.linuxOnDemandPrice }
    }

    private List<HardwareProfile> retrieveHardwareProfiles() {
        // Some day it would be nice to have a reliable API to call for this data periodically. For now, this will do.
        String xl = 'Extra Large'
        String xl2 = 'Double Extra Large'
        String xl4 = 'Quadruple Extra Large'
        String xl8 = '8 Extra Large'
        String xl9 = '9 Extra Large'
        String xl10 = '10 Extra Large'
        String xl12 = '12 Extra Large'
        String xl18 = '18 Extra Large'
        String xl24 = '24 Extra Large'
        String gen = 'General purpose'
        String second = 'Second Generation Standard'
        String cc = 'Cluster Compute'
        String memOpt = 'Memory optimized'
        String hiMem = 'High-Memory'
        String compOpt = 'Compute optimized'
        String bInst = 'Burstable Performance Instances'
        String six4 = '64-bit'
        String three2OrSix4 = '32-bit or 64-bit'
        String hcpu = 'High-CPU'
        [
                new HardwareProfile(instanceType: 't1.micro', family: 'Micro instances', group: 'Micro', size: 'Micro',
                        arch: three2OrSix4, vCpu: '1', ecu: 'Variable', mem: '0.615', storage: 'EBS only',
                        ebsOptim: '-', netPerf: 'Very Low'),

                new HardwareProfile(instanceType: 't2.nano', family: bInst, group: gen, size: 'Nano',
                        arch: six4, vCpu: '1', ecu: 'Variable', mem: '0.5', storage: 'EBS only',  ebsOptim: '-',
                        netPerf: 'Low'),
                new HardwareProfile(instanceType: 't2.micro', family: bInst, group: gen, size: 'Micro',
                        arch: three2OrSix4, vCpu: '1', ecu: 'Variable', mem: '1', storage: 'EBS only',  ebsOptim: '-',
                        netPerf: 'Low to Moderate'),
                new HardwareProfile(instanceType: 't2.small', family: bInst, group: gen, size: 'Small',
                        arch: three2OrSix4, vCpu: '1', ecu: 'Variable', mem: '2', storage: 'EBS only',  ebsOptim: '-',
                        netPerf: 'Low to Moderate'),
                new HardwareProfile(instanceType: 't2.medium', family: bInst, group: gen, size: 'Medium',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '4', storage: 'EBS only',  ebsOptim: '-',
                        netPerf: 'Low to Moderate'),
                new HardwareProfile(instanceType: 't2.large', family: bInst, group: gen, size: 'Large',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '8', storage: 'EBS only',  ebsOptim: '-',
                        netPerf: 'Low to Moderate'),

                new HardwareProfile(instanceType: 't3.nano', family: bInst, group: gen, size: 'Nano',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '0.5', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.micro', family: bInst, group: gen, size: 'Micro',
                        arch: three2OrSix4, vCpu: '2', ecu: 'Variable', mem: '1', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.small', family: bInst, group: gen, size: 'Small',
                        arch: three2OrSix4, vCpu: '2', ecu: 'Variable', mem: '2', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.medium', family: bInst, group: gen, size: 'Medium',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '4', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.large', family: bInst, group: gen, size: 'Large',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '8', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.xlarge', family: bInst, group: gen, size: xl,
                        arch: six4, vCpu: '4', ecu: 'Variable', mem: '16', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3.2xlarge', family: bInst, group: gen, size: xl2,
                        arch: six4, vCpu: '8', ecu: 'Variable', mem: '32', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),

                new HardwareProfile(instanceType: 't3a.nano', family: bInst, group: gen, size: 'Nano',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '0.5', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.micro', family: bInst, group: gen, size: 'Micro',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '1', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.small', family: bInst, group: gen, size: 'Small',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '2', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.medium', family: bInst, group: gen, size: 'Medium',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '4', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.large', family: bInst, group: gen, size: 'Large',
                        arch: six4, vCpu: '2', ecu: 'Variable', mem: '8', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.xlarge', family: bInst, group: gen, size: xl,
                        arch: six4, vCpu: '4', ecu: 'Variable', mem: '16', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),
                new HardwareProfile(instanceType: 't3a.2xlarge', family: bInst, group: gen, size: xl2,
                        arch: six4, vCpu: '8', ecu: 'Variable', mem: '32', storage: 'EBS only',  ebsOptim: 'Yes',
                        netPerf: 'Up to 5'),

                new HardwareProfile(instanceType: 'm4.large', family: gen, group: gen, size: 'Large', arch: six4,
                        vCpu: '2', ecu: '6.5', mem: '8', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Moderate'),
                new HardwareProfile(instanceType: 'm4.xlarge', family: gen, group: gen, size: xl, arch: six4,
                        vCpu: '4', ecu: '13', mem: '16', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'm4.2xlarge', family: gen, group: gen, size: xl2, arch: six4,
                        vCpu: '8', ecu: '26', mem: '32', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'm4.4xlarge', family: gen, group: gen, size: xl4, arch: six4,
                        vCpu: '16', ecu: '53.5', mem: '64', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'm4.10xlarge', family: gen, group: gen, size: xl10, arch: six4,
                        vCpu: '40', ecu: '124.5', mem: '160', storage: 'EBS only', ebsOptim: 'Yes', netPerf: '10 Gigabit'),

                new HardwareProfile(instanceType: 'm5.large', family: gen, group: gen, size: 'Large', arch: six4,
                        vCpu: '2', ecu: '8', mem: '8', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5.xlarge', family: gen, group: gen, size: xl, arch: six4,
                        vCpu: '4', ecu: '16', mem: '16', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5.2xlarge', family: gen, group: gen, size: xl2, arch: six4,
                        vCpu: '8', ecu: '31', mem: '32', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5.4xlarge', family: gen, group: gen, size: xl4, arch: six4,
                        vCpu: '16', ecu: '60', mem: '64', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5.12xlarge', family: gen, group: gen, size: xl12, arch: six4,
                        vCpu: '48', ecu: '173', mem: '192', storage: 'EBS only', ebsOptim: 'Yes', netPerf: '10 Gigabit'),
                new HardwareProfile(instanceType: 'm5.24xlarge', family: gen, group: gen, size: xl24, arch: six4,
                        vCpu: '96', ecu: '345', mem: '384', storage: 'EBS only', ebsOptim: 'Yes', netPerf: '25 Gigabit'),

                new HardwareProfile(instanceType: 'm5a.large', family: gen, group: gen, size: 'Large', arch: six4,
                        vCpu: '2', ecu: '8', mem: '8', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5a.xlarge', family: gen, group: gen, size: xl, arch: six4,
                        vCpu: '4', ecu: '16', mem: '16', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5a.2xlarge', family: gen, group: gen, size: xl2, arch: six4,
                        vCpu: '8', ecu: '31', mem: '32', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5a.4xlarge', family: gen, group: gen, size: xl4, arch: six4,
                        vCpu: '16', ecu: '60', mem: '64', storage: 'EBS only', ebsOptim: 'Yes', netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'm5a.12xlarge', family: gen, group: gen, size: xl12, arch: six4,
                        vCpu: '48', ecu: '173', mem: '192', storage: 'EBS only', ebsOptim: 'Yes', netPerf: '10 Gigabit'),
                new HardwareProfile(instanceType: 'm5a.24xlarge', family: gen, group: gen, size: xl24, arch: six4,
                        vCpu: '96', ecu: '345', mem: '384', storage: 'EBS only', ebsOptim: 'Yes', netPerf: '25 Gigabit'),

                new HardwareProfile(instanceType: 'c4.large', family: compOpt, group: hcpu, size: 'Large',
                        arch: six4, vCpu: '2', ecu: '8', mem: '3.75', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'Moderate'),
                new HardwareProfile(instanceType: 'c4.xlarge', family: compOpt, group: hcpu, size: xl,
                        arch: six4, vCpu: '4', ecu: '16', mem: '7.5', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'High'),
                new HardwareProfile(instanceType: 'c4.2xlarge', family: compOpt, group: hcpu, size: xl2,
                        arch: six4, vCpu: '8', ecu: '31', mem: '15', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'High'),
                new HardwareProfile(instanceType: 'c4.4xlarge', family: compOpt, group: hcpu, size: xl4,
                        arch: six4, vCpu: '16', ecu: '62', mem: '30', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'High'),
                new HardwareProfile(instanceType: 'c4.8xlarge', family: compOpt, group: hcpu, size: xl8,
                        arch: six4, vCpu: '36', ecu: '132', mem: '60', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: '10 Gigabit'),

                new HardwareProfile(instanceType: 'c5.large', family: compOpt, group: hcpu, size: 'Large',
                        arch: six4, vCpu: '2', ecu: '9', mem: '4', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5.xlarge', family: compOpt, group: hcpu, size: xl,
                        arch: six4, vCpu: '4', ecu: '17', mem: '8', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5.2xlarge', family: compOpt, group: hcpu, size: xl2,
                        arch: six4, vCpu: '8', ecu: '34', mem: '16', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5.4xlarge', family: compOpt, group: hcpu, size: xl4,
                        arch: six4, vCpu: '16', ecu: '68', mem: '32', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5.9xlarge', family: compOpt, group: hcpu, size: xl9,
                        arch: six4, vCpu: '36', ecu: '141', mem: '72', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: '10 Gigabit'),
                new HardwareProfile(instanceType: 'c5.18xlarge', family: compOpt, group: hcpu, size: xl18,
                        arch: six4, vCpu: '72', ecu: '281', mem: '144', storage: 'EBS Only', ebsOptim: 'Yes',
                        netPerf: '25'),

                new HardwareProfile(instanceType: 'c5d.large', family: compOpt, group: hcpu, size: 'Large',
                        arch: six4, vCpu: '2', ecu: '9', mem: '4', storage: '1x50 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5d.xlarge', family: compOpt, group: hcpu, size: xl,
                        arch: six4, vCpu: '4', ecu: '17', mem: '8', storage: '1x100 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5d.2xlarge', family: compOpt, group: hcpu, size: xl2,
                        arch: six4, vCpu: '8', ecu: '34', mem: '16', storage: '1x200 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5d.4xlarge', family: compOpt, group: hcpu, size: xl4,
                        arch: six4, vCpu: '16', ecu: '68', mem: '32', storage: '1x400 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: 'Up to 10'),
                new HardwareProfile(instanceType: 'c5d.9xlarge', family: compOpt, group: hcpu, size: xl9,
                        arch: six4, vCpu: '36', ecu: '141', mem: '72', storage: '1x900 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: '10 Gigabit'),
                new HardwareProfile(instanceType: 'c5d.18xlarge', family: compOpt, group: hcpu, size: xl18,
                        arch: six4, vCpu: '72', ecu: '281', mem: '144', storage: '2x900 NVMe SSD', ebsOptim: 'Yes',
                        netPerf: '25 Gigabit'),

                new HardwareProfile(instanceType: 'r3.large', family: memOpt, group: hiMem, size: 'Large', arch: six4,
                        vCpu: '2', ecu: '6.5', mem: '15', storage: '1 x 32', ebsOptim: '-', netPerf: 'High'),
                new HardwareProfile(instanceType: 'r3.xlarge', family: memOpt, group: hiMem, size: xl, arch: six4,
                        vCpu: '4', ecu: '13', mem: '30.5', storage: '1 x 80', ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'r3.2xlarge', family: memOpt, group: hiMem, size: xl2, arch: six4,
                        vCpu: '8', ecu: '26', mem: '61', storage: '1 x 160', ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'r3.4xlarge', family: memOpt, group: hiMem, size: xl4,
                        arch: six4, vCpu: '16', ecu: '52', mem: '122', storage: '1 x 320', ebsOptim: 'Yes',
                        netPerf: 'High'),
                new HardwareProfile(instanceType: 'r3.8xlarge', family: memOpt, group: hiMem, size: xl8,
                        arch: six4, vCpu: '32', ecu: '104', mem: '244', storage: '2 x 320', ebsOptim: '-',
                        netPerf: 'High'),

                new HardwareProfile(instanceType: 'cr1.8xlarge', family: memOpt, group: 'High-Memory Cluster',
                        size: xl8, arch: six4, vCpu: '32', ecu: '88', mem: '244', storage: '2 x 120 SSD',
                        ebsOptim: '-', netPerf: '10 Gigabit'),

                new HardwareProfile(instanceType: 'cg1.4xlarge', family: 'GPU instances', group: 'Cluster GPU',
                        size: xl4, arch: six4, vCpu: '16', ecu: '33.5', mem: '22.5', storage: '2 x 840',
                        ebsOptim: '-', netPerf: '10 Gigabit'),

                new HardwareProfile(instanceType: 'hi1.4xlarge', family: 'Storage optimized', group: 'High-I/O',
                        size: xl4, arch: six4, vCpu: '16', ecu: '35', mem: '60.5', storage: '2 x 1,024 SSD',
                        ebsOptim: '-', netPerf: '10 Gigabit'),
                new HardwareProfile(instanceType: 'hs1.8xlarge', family: 'Storage optimized', group: 'High-Storage',
                        size: xl8, arch: six4, vCpu: '16', ecu: '35', mem: '117', storage: '24 x 2,048',
                        ebsOptim: '-', netPerf: '10 Gigabit'),

                new HardwareProfile(instanceType: 'i2.xlarge', family: 'Storage optimized', group: 'High-Storage',
                        size: xl, arch: six4, vCpu: '4', ecu: '14', mem: '30.5', storage: '1 x 800 SSD',
                        ebsOptim: 'Yes', netPerf: 'Moderate'),
                new HardwareProfile(instanceType: 'i2.2xlarge', family: 'Storage optimized', group: 'High-Storage',
                        size: xl2, arch: six4, vCpu: '8', ecu: '27', mem: '61', storage: '2 x 800 SSD',
                        ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'i2.4xlarge', family: 'Storage optimized', group: 'High-Storage',
                        size: xl4, arch: six4, vCpu: '16', ecu: '53', mem: '122', storage: '4 x 800 SSD',
                        ebsOptim: 'Yes', netPerf: 'High'),
                new HardwareProfile(instanceType: 'i2.8xlarge', family: 'Storage optimized', group: 'High-Storage',
                        size: xl8, arch: six4, vCpu: '32', ecu: '104', mem: '244', storage: '8 x 800 SSD',
                        ebsOptim: 'Yes', netPerf: '10 Gigabit')
        ]
    }

    private Map<Region, RegionalInstancePrices> retrieveInstanceTypePricing(InstancePriceType priceType) {
        Map<Region, RegionalInstancePrices> regionsToPricingData = [:]
        JSONElement pricingJson = fetchPricingJsonData(priceType)
        JSONElement config = pricingJson.config
        JSONArray regionJsonArray = config.regions
        for (JSONElement regionJsonObject in regionJsonArray) {
            Region region = Region.withCode(regionJsonObject.region)
            List<InstanceType> instanceTypes = InstanceType.values() as List
            List<InstanceProductType> products = InstanceProductType.values() as List
            Table<InstanceType, InstanceProductType, BigDecimal> pricesByHardwareAndProduct =
                    ArrayTable.create(instanceTypes, products)
            JSONArray typesJsonArray = regionJsonObject.instanceTypes
            for (JSONElement typeJsonObject in typesJsonArray) {
                JSONArray sizes = typeJsonObject.sizes
                for (JSONElement sizeObject in sizes) {
                    String sizeCode = sizeObject.size
                    InstanceType instanceType = determineInstanceType(sizeCode)
                    if (instanceType) {
                        JSONArray valueColumns = sizeObject.valueColumns
                        for (JSONElement valueColumn in valueColumns) {
                            InstanceProductType product = products.find { it.didProductTypeMatch(valueColumn.name) }
                            if (product) {
                                String priceString = valueColumn.prices.USD
                                if (priceString?.isBigDecimal()) {
                                    BigDecimal price = priceString.toBigDecimal()
                                    pricesByHardwareAndProduct.put(instanceType, product, price)
                                }
                            }
                        }
                    }
                }
            }
            regionsToPricingData[region] = RegionalInstancePrices.create(pricesByHardwareAndProduct)
        }
        return regionsToPricingData
    }

    private Map<Region, RegionalInstancePrices> retrieveInstanceTypeOnDemandPricing() {
        retrieveInstanceTypePricing(InstancePriceType.ON_DEMAND)
    }

    private InstanceType determineInstanceType(String jsonSizeCode) {
        try {
            return InstanceType.fromValue(jsonSizeCode)
        } catch (IllegalArgumentException ignore) {
            return null
        }
    }
}

class RegionalInstancePrices {
    final Table<InstanceType, InstanceProductType, BigDecimal> pricesByHardwareAndProduct

    private RegionalInstancePrices(Table<InstanceType, InstanceProductType, BigDecimal> pricesByHardwareAndProduct) {
        this.pricesByHardwareAndProduct = pricesByHardwareAndProduct
    }

    static create(Table<InstanceType, InstanceProductType, BigDecimal> pricesByHardwareAndProduct) {
        new RegionalInstancePrices(pricesByHardwareAndProduct)
    }

    BigDecimal get(InstanceType instanceType, InstanceProductType instanceProductType) {
        pricesByHardwareAndProduct.get(instanceType, instanceProductType)
    }

    String toString() {
        pricesByHardwareAndProduct.toString()
    }
}
