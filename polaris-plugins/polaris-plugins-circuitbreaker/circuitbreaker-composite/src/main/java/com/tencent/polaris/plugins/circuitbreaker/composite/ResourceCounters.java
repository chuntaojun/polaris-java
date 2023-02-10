/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.plugins.circuitbreaker.composite;

import static com.tencent.polaris.logging.LoggingConsts.LOGGING_CIRCUITBREAKER_EVENT;

import com.tencent.polaris.api.plugin.circuitbreaker.ResourceStat;
import com.tencent.polaris.api.plugin.circuitbreaker.entity.Resource;
import com.tencent.polaris.api.pojo.CircuitBreakerStatus;
import com.tencent.polaris.api.pojo.CircuitBreakerStatus.FallbackInfo;
import com.tencent.polaris.api.pojo.CircuitBreakerStatus.Status;
import com.tencent.polaris.api.pojo.HalfOpenStatus;
import com.tencent.polaris.api.pojo.RetStatus;
import com.tencent.polaris.api.utils.RuleUtils;
import com.tencent.polaris.logging.LoggerFactory;
import com.tencent.polaris.plugins.circuitbreaker.composite.trigger.ConsecutiveCounter;
import com.tencent.polaris.plugins.circuitbreaker.composite.trigger.CounterOptions;
import com.tencent.polaris.plugins.circuitbreaker.composite.trigger.ErrRateCounter;
import com.tencent.polaris.plugins.circuitbreaker.composite.trigger.TriggerCounter;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.CircuitBreakerRule;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.ErrorCondition;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.FallbackConfig;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.FallbackResponse;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.FallbackResponse.MessageHeader;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.Level;
import com.tencent.polaris.specification.api.v1.fault.tolerance.CircuitBreakerProto.TriggerCondition;
import com.tencent.polaris.specification.api.v1.model.ModelProto.MatchString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;

public class ResourceCounters implements StatusChangeHandler {

    private static final Logger CB_EVENT_LOG = LoggerFactory.getLogger(LOGGING_CIRCUITBREAKER_EVENT);

    private final CircuitBreakerProto.CircuitBreakerRule currentActiveRule;

    private final List<TriggerCounter> counters = new ArrayList<>();

    private final Resource resource;

    private final ScheduledExecutorService stateChangeExecutors;

    private final AtomicReference<CircuitBreakerStatus> circuitBreakerStatusReference = new AtomicReference<>();

    private final AtomicInteger halfOpenSuccess = new AtomicInteger(0);

    private final FallbackInfo fallbackInfo;

    private final int consecutiveSuccessCount;

    public ResourceCounters(Resource resource, CircuitBreakerRule currentActiveRule,
            ScheduledExecutorService stateChangeExecutors) {
        this.currentActiveRule = currentActiveRule;
        this.resource = resource;
        this.stateChangeExecutors = stateChangeExecutors;
        circuitBreakerStatusReference
                .set(new CircuitBreakerStatus(currentActiveRule.getName(), Status.CLOSE, System.currentTimeMillis()));
        fallbackInfo = buildFallbackInfo(currentActiveRule);
        consecutiveSuccessCount = currentActiveRule.getRecoverCondition().getConsecutiveSuccess();
        init();
    }

    private void init() {
        List<TriggerCondition> triggerConditionList = currentActiveRule.getTriggerConditionList();
        for (TriggerCondition triggerCondition : triggerConditionList) {
            CounterOptions counterOptions = new CounterOptions();
            counterOptions.setResource(resource);
            counterOptions.setTriggerCondition(triggerCondition);
            counterOptions.setStatusChangeHandler(this);
            counterOptions.setExecutorService(stateChangeExecutors);
            switch (triggerCondition.getTriggerType()) {
                case ERROR_RATE:
                    counters.add(new ErrRateCounter(currentActiveRule.getName(), counterOptions));
                    break;
                case CONSECUTIVE_ERROR:
                    counters.add(new ConsecutiveCounter(currentActiveRule.getName(), counterOptions));
                    break;
                default:
                    break;
            }
        }
    }

    private static FallbackInfo buildFallbackInfo(CircuitBreakerRule currentActiveRule) {
        if (null == currentActiveRule) {
            return null;
        }
        if (currentActiveRule.getLevel() != Level.METHOD && currentActiveRule.getLevel() != Level.SERVICE) {
            return null;
        }
        FallbackConfig fallbackConfig = currentActiveRule.getFallbackConfig();
        if (null == fallbackConfig || !fallbackConfig.getEnable()) {
            return null;
        }
        FallbackResponse response = fallbackConfig.getResponse();
        if (null == response) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        for (MessageHeader messageHeader : response.getHeadersList()) {
            headers.put(messageHeader.getKey(), messageHeader.getValue());
        }
        return new FallbackInfo(response.getCode(), headers, response.getBody());
    }

    public CircuitBreakerRule getCurrentActiveRule() {
        return currentActiveRule;
    }

    @Override
    public void closeToOpen(String circuitBreaker) {
        synchronized (this) {
            CircuitBreakerStatus circuitBreakerStatus = circuitBreakerStatusReference.get();
            if (circuitBreakerStatus.getStatus() == Status.CLOSE) {
                toOpen(circuitBreakerStatus, circuitBreaker);
            }
        }
    }

    private void toOpen(CircuitBreakerStatus preStatus, String circuitBreaker) {
        CircuitBreakerStatus newStatus = new CircuitBreakerStatus(circuitBreaker, Status.OPEN,
                System.currentTimeMillis(), fallbackInfo);
        circuitBreakerStatusReference.set(newStatus);
        CB_EVENT_LOG.info("previous status {}, current status {}, resource {}, rule {}", preStatus.getStatus(),
                newStatus.getStatus(), resource, circuitBreaker);
        int sleepWindow = currentActiveRule.getRecoverCondition().getSleepWindow();
        // add callback after timeout
        stateChangeExecutors.schedule(new Runnable() {
            @Override
            public void run() {
                openToHalfOpen();
            }
        }, sleepWindow, TimeUnit.SECONDS);
    }

    @Override
    public void openToHalfOpen() {
        synchronized (this) {
            CircuitBreakerStatus circuitBreakerStatus = circuitBreakerStatusReference.get();
            if (circuitBreakerStatus.getStatus() != Status.OPEN) {
                return;
            }
            int consecutiveSuccess = currentActiveRule.getRecoverCondition().getConsecutiveSuccess();
            halfOpenSuccess.set(0);
            HalfOpenStatus halfOpenStatus = new HalfOpenStatus(
                    circuitBreakerStatus.getCircuitBreaker(), System.currentTimeMillis(), consecutiveSuccess);
            CB_EVENT_LOG.info("previous status {}, current status {}, resource {}, rule {}",
                    circuitBreakerStatus.getStatus(),
                    halfOpenStatus.getStatus(), resource, circuitBreakerStatus.getCircuitBreaker());
            circuitBreakerStatusReference.set(halfOpenStatus);
        }
    }

    private void checkHalfOpenConversion() {
        int halfOpenSuccessCount = halfOpenSuccess.get();
        if (halfOpenSuccessCount >= consecutiveSuccessCount) {
            CB_EVENT_LOG.debug("halfOpenSuccessCount " + halfOpenSuccessCount + ", consecutiveSuccessCount " +
                    consecutiveSuccessCount + ", do halfOpenToClose");
            halfOpenToClose();
        } else {
            CB_EVENT_LOG.debug("halfOpenSuccessCount " + halfOpenSuccessCount + ", consecutiveSuccessCount "
                    + consecutiveSuccessCount + ", do halfOpenToOpen");
            halfOpenToOpen();
        }
    }

    @Override
    public void halfOpenToClose() {
        synchronized (this) {
            CircuitBreakerStatus circuitBreakerStatus = circuitBreakerStatusReference.get();
            if (circuitBreakerStatus.getStatus() == Status.HALF_OPEN) {
                CircuitBreakerStatus newStatus = new CircuitBreakerStatus(circuitBreakerStatus.getCircuitBreaker(),
                        Status.CLOSE, System.currentTimeMillis());
                circuitBreakerStatusReference.set(newStatus);
                CB_EVENT_LOG.info("previous status {}, current status {}, resource {}, rule {}",
                        circuitBreakerStatus.getStatus(),
                        newStatus.getStatus(), resource, circuitBreakerStatus.getCircuitBreaker());
                for (TriggerCounter triggerCounter : counters) {
                    triggerCounter.resume();
                }
            }
        }
    }

    @Override
    public void halfOpenToOpen() {
        synchronized (this) {
            CircuitBreakerStatus circuitBreakerStatus = circuitBreakerStatusReference.get();
            if (circuitBreakerStatus.getStatus() == Status.HALF_OPEN) {
                toOpen(circuitBreakerStatus, circuitBreakerStatus.getCircuitBreaker());
            }
        }
    }

    public void report(ResourceStat resourceStat) {
        List<ErrorCondition> errorConditionsList = currentActiveRule.getErrorConditionsList();
        Function<String, Pattern> function = new Function<String, Pattern>() {
            @Override
            public Pattern apply(String s) {
                return Pattern.compile(s);
            }
        };
        boolean success = true;
        RetStatus retStatus = resourceStat.getRetStatus();
        if (retStatus == RetStatus.RetSuccess) {
            success = true;
        } else if (retStatus == RetStatus.RetFail) {
            success = false;
        } else {
            for (ErrorCondition errorCondition : errorConditionsList) {
                MatchString condition = errorCondition.getCondition();
                switch (errorCondition.getInputType()) {
                    case RET_CODE:
                        boolean codeMatched = RuleUtils
                                .matchStringValue(condition, String.valueOf(resourceStat.getRetCode()), function);
                        if (codeMatched) {
                            success = false;
                        }
                        break;
                    case DELAY:
                        String value = condition.getValue().getValue();
                        int delayValue = Integer.parseInt(value);
                        if (resourceStat.getDelay() >= delayValue) {
                            success = false;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        CircuitBreakerStatus circuitBreakerStatus = circuitBreakerStatusReference.get();
        if (null != circuitBreakerStatus && circuitBreakerStatus.getStatus() == Status.HALF_OPEN) {
            HalfOpenStatus halfOpenStatus = (HalfOpenStatus) circuitBreakerStatus;
            if (success) {
                int count = halfOpenSuccess.incrementAndGet();
                if (count >= consecutiveSuccessCount) {
                    scheduleHalfOpenConversion(halfOpenStatus);
                }
            } else {
                halfOpenSuccess.set(0);
                scheduleHalfOpenConversion(halfOpenStatus);
            }
        } else {
            for (TriggerCounter counter : counters) {
                counter.report(success);
            }
        }
    }

    private void scheduleHalfOpenConversion(HalfOpenStatus halfOpenStatus) {
        if (halfOpenStatus.schedule()) {
            stateChangeExecutors.schedule(new Runnable() {
                @Override
                public void run() {
                    checkHalfOpenConversion();
                }
            }, 1, TimeUnit.SECONDS);
        }
    }


    public CircuitBreakerStatus getCircuitBreakerStatus() {
        return circuitBreakerStatusReference.get();
    }
}
