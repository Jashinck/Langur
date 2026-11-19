package org.skylark.langur.infrastructure.llm;

import org.skylark.langur.domain.port.LLMPort;

public interface ModelRoutableLLMPort extends LLMPort {

    String getProviderName();

    boolean supportsModel(String model);
}
