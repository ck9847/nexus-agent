package com.nexusagent.tool.internal;

public interface CreateTicketToolJsonCodec {

    CreateTicketToolArguments decodeArguments(
            String inputJson
    );

    String encodeOutput(CreateTicketToolOutput output);

    CreateTicketToolOutput decodeOutput(String outputJson);
}
