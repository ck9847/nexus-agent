package com.nexusagent.common.observability;

import java.util.Optional;

/**
 * 提供当前请求的关联上下文。
 *
 * <p>{@link Optional#empty()} 表示当前线程确实没有请求关联
 * （例如后台任务、无 HTTP 上下文）——这是正常语义，不是故障。
 * 实现自身的真实故障（例如底层上下文读取抛错）必须作为异常
 * 向外传播，调用方绝不能把异常误判为"没有上下文"。
 */
public interface RequestCorrelationProvider {

    Optional<RequestCorrelation> currentCorrelation();
}
