package com.mycelium.spvmodule.model;

import com.google.common.base.Optional;
import com.mycelium.spvmodule.currency.CurrencyValue;


import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;

import java.util.List;


public class TransactionSummary implements Comparable<TransactionSummary> {
    public final Sha256Hash txid;
    public final CurrencyValue value;
    public final boolean isIncoming;
    public final long time;
    public final int height;
    public final int confirmations;
    public final boolean isQueuedOutgoing;
    public final Optional<ConfirmationRiskProfileLocal> confirmationRiskProfile;
    public final Optional<Address> destinationAddress;
    public final List<Address> toAddresses;

    public TransactionSummary(Sha256Hash txid, CurrencyValue value, boolean isIncoming, long time, int height,
                              int confirmations, boolean isQueuedOutgoing, ConfirmationRiskProfileLocal confirmationRiskProfile,
                              Optional<Address> destinationAddress, List<Address> toAddresses) {
        this.txid = txid;
        this.value = value;
        this.isIncoming = isIncoming;
        this.time = time;
        this.height = height;
        this.confirmations = confirmations;
        this.isQueuedOutgoing = isQueuedOutgoing;
        this.confirmationRiskProfile = Optional.fromNullable(confirmationRiskProfile);
        this.destinationAddress = destinationAddress;
        this.toAddresses = toAddresses;
    }

    @Override
    public int compareTo(TransactionSummary other) {
        // First sort by confirmations
        if (confirmations < other.confirmations) {
            return 1;
        } else if (confirmations > other.confirmations) {
            return -1;
        } else {
            // Then sort by outgoing status
            if (isQueuedOutgoing != other.isQueuedOutgoing) {
                return isQueuedOutgoing ? 1 : -1;
            }
            // Finally sort by time
            if (time < other.time) {
                return -1;
            } else if (time > other.time) {
                return 1;
            }
            return 0;
        }
    }

    @Override
    public int hashCode() {
        return txid.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof TransactionSummary)) {
            return false;
        }
        TransactionSummary other = (TransactionSummary) obj;
        return other.txid.equals(this.txid);
    }

    public boolean canCancel() {
        return isQueuedOutgoing;
    }

    public boolean hasAddressBook() {
        return destinationAddress.isPresent();
    }

    public boolean hasDetails() {
        return true;
    }

    public boolean canCoinapult() {
        return false;
    }
}

