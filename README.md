# SPV Bitcoin Cash Module

The SPV Bitcoin Cash Module is a proxy to the blockchain.

This module supports

* SPV connection to the bitcoin cash network
* broadcast transactions
* receive transactions for addresses
* receive transactions for addresses using a Bloom filter (standard Schildbach-style)
* connect to own full node
* TODO: connect using TOR
* TODO: [Committed bloom filters](https://lists.linuxfoundation.org/pipermail/bitcoin-dev/2016-May/012636.html)
* for convenience, consuming modules can assign an account to addresses, TODO: which also gets replicated to the found transactions

## SPV connection to the bitcoin cash network

Using [bitcoincashj](https://github.com/mycelium-com/bitcoincashj/tree/multiple-hd-accounts).

## Broadcast transactions

To broadcast transactions, a module needs the serialized form of the signed transaction.

        Intent intent = new Intent("com.mycelium.wallet.broadcastTransaction");
        intent.putExtra("TX", transactionBytes);
        sendBroadcast(intent, "com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION");

The SPV Module will receive the Intent and broadcast the transaction.
The reception gets confirmed in form of a replied TXID:

        results.putString("broadcastTX", tx.getHashAsString());

## Receive transactions for addresses

The SPV Module can check balances and the transaction history of lists of transactions either with
the plain list of addresses of interest or with a bloom filter.
To request this, broadcast

        Intent intent = new Intent("com.mycelium.wallet.receiveTransactions");
        intent.putExtra("ADDRESSES", addressArray);
        sendBroadcast(intent, "com.mycelium.wallet.RECEIVE_TRANSACTIONS");

# Thoughts on module management

While Android does allow to limit communication in the way desired by us, it's not trivial to make it water tight.
An app can define custom permissions. `"com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION"` for example is defined in the core wallet's manifest using

        <permission-group
            android:name="com.mycelium.wallet.permission-group.PRIVACY"
            android:icon="@drawable/permissiongroupprivacy"
            android:label="@string/permgrouplab_privacy"
            android:description="@string/permgroupdesc_privacy"
            android:priority="100" />
        <permission
            android:name="com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION"
            android:protectionLevel="signature"
            android:label="@string/label_broadcast_signed_tx"
            android:description="@string/desc_broadcast_signed_tx"
            android:permissionGroup="com.mycelium.wallet.permission-group.PRIVACY"/>

and SPV Module declares its use in its manifest

        <uses-permission android:name="com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION"/>

Without this line, SPV Module just doesn't get triggered if the broadcast was sent using the permission like this:

        sendBroadcast(intent, "com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION");

But a normal user does not necessarily notice or understand these extra permissions and it's probably not even a good idea to make them very visible in the play store listing.
It would probably also make sense to verify these permissions for generated APKs and not just in code reviews to not get fooled by some kind of [Manifest merging](https://developer.android.com/studio/build/manifest-merge.html).
It is not clear where to put such tests in our deploy pipeline. Release builds might use a different Manifest than debug builds that are built during CI.

Anyway, `aapt` can read the permissions from an apk file

        $ aapt d permissions spvmodule/build/outputs/apk/spvmodule-debug.apk
        package: com.mycelium.spvmodule
        uses-permission: name='android.permission.INTERNET'
        uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
        uses-permission: name='com.mycelium.wallet.BROADCAST_SIGNED_TRANSACTION'



