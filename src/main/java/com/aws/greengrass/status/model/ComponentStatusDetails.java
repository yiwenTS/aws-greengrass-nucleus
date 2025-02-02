/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import com.aws.greengrass.dependency.State;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentStatusDetails {
    private String componentName;

    private String version;

    private List<String> fleetConfigArns;

    private String statusDetails;

    // We need to add this since during serialization, the 'is' is removed.
    @JsonProperty("isRoot")
    private boolean isRoot;

    @JsonProperty("status")
    private State state;
}
