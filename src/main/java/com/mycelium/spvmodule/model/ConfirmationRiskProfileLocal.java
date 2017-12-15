package com.mycelium.spvmodule.model;

public class ConfirmationRiskProfileLocal extends ConfirmationRiskProfile {
    public final boolean isDoubleSpend;

    public ConfirmationRiskProfileLocal(int unconfirmedChainLength, boolean hasRbfRisk, boolean isDoubleSpend) {
        super(unconfirmedChainLength, hasRbfRisk);
        this.isDoubleSpend = isDoubleSpend;
    }
}
