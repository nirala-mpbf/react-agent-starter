package com.meg.agentsdk.prompt.dto;

import jakarta.validation.constraints.NotBlank;

public record PromptLookupRequest(
        @NotBlank String workflow,
        @NotBlank String kind
) {}
