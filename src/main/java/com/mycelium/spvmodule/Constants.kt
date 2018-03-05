package com.mycelium.spvmodule

import android.os.Environment
import android.text.format.DateUtils
import org.bitcoinj.core.Coin

import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params

interface Constants {
    interface Files {
        companion object {
            val _FILENAME_NETWORK_SUFFIX = if (NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET) "" else "-testnet"

            /**
             * Filename of the wallet.
             */
            val WALLET_FILENAME_PROTOBUF = "wallet-protobuf" + _FILENAME_NETWORK_SUFFIX

            /**
             * Filename of the automatic key backup (old format, can only be read).
             */
            val WALLET_KEY_BACKUP_BASE58 = "key-backup-base58" + _FILENAME_NETWORK_SUFFIX

            /**
             * Filename of the automatic wallet backup.
             */
            val WALLET_KEY_BACKUP_PROTOBUF = "key-backup-protobuf" + _FILENAME_NETWORK_SUFFIX

            /**
             * Path to external storage
             */
            val EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory()

            /**
             * Manual backups go here.
             */
            val EXTERNAL_WALLET_BACKUP_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            /**
             * Filename of the manual key backup (old format, can only be read).
             */
            val EXTERNAL_WALLET_KEY_BACKUP = "bitcoin-wallet-keys" + _FILENAME_NETWORK_SUFFIX

            /**
             * Filename of the manual wallet backup.
             */
            val EXTERNAL_WALLET_BACKUP = "bitcoin-wallet-backup" + _FILENAME_NETWORK_SUFFIX

            /**
             * Filename of the block store for storing the chain.
             */
            val BLOCKCHAIN_FILENAME = "blockchain" + _FILENAME_NETWORK_SUFFIX

            /**
             * Filename of the block checkpoints file.
             */
            val CHECKPOINTS_FILENAME = "checkpoints-bch$_FILENAME_NETWORK_SUFFIX.txt"
        }
    }

    companion object {
        val TAG = "c.m.spv"
        val TEST = BuildConfig.APPLICATION_ID.contains("test")

        /**
         * Network this wallet is on (e.g. testnet or mainnet).
         */
        val NETWORK_PARAMETERS: NetworkParameters = if (TEST) TestNet3Params.get() else MainNetParams.get()

        /**
         * Bitcoinj global context.
         */
        val CONTEXT = Context(NETWORK_PARAMETERS)

        /**
         * Maximum size of backups. Files larger will be rejected.
         */
        val BACKUP_MAX_CHARS: Long = 10000000

        /**
         * MIME type used for transmitting single transactions.
         */
        val MIMETYPE_TRANSACTION = "application/x-btctx"

        /**
         * User-agent to use for network access.
         */
        val USER_AGENT = "Mycelium SPV Module"

        /**
         * Subject line for crash reports.
         */
        val REPORT_SUBJECT_CRASH = "Crash report"

        val CHAR_THIN_SPACE = '\u2009'

        val HTTP_TIMEOUT_MS = 15 * DateUtils.SECOND_IN_MILLIS.toInt()
        val PEER_DISCOVERY_TIMEOUT_MS = 10 * DateUtils.SECOND_IN_MILLIS.toInt()
        val PEER_TIMEOUT_MS = 15 * DateUtils.SECOND_IN_MILLIS.toInt()

        val LAST_USAGE_THRESHOLD_JUST_MS = DateUtils.HOUR_IN_MILLIS
        val LAST_USAGE_THRESHOLD_RECENTLY_MS = 2 * DateUtils.DAY_IN_MILLIS
        val LAST_USAGE_THRESHOLD_INACTIVE_MS = 4 * DateUtils.WEEK_IN_MILLIS

        val DELAYED_TRANSACTION_THRESHOLD_MS = 2 * DateUtils.HOUR_IN_MILLIS

        val MEMORY_CLASS_LOWEND = 48

        val NOTIFICATION_ID_CONNECTED = 0
        val NOTIFICATION_ID_COINS_RECEIVED = 1
        val NOTIFICATION_ID_INACTIVITY = 2

        val QR_ADDRESS_PREFIX = "bitcoincash:"

        /**
         * Returns fee per kB adjusted by txFeeFactor
         *
         * @param txFeeLevel Transaction fee level {@link com.mycelium.spvmodule.TransactionFee}
         * @param txFeeFactor conceptual parameter that could be used to adjust the value of basic fee,
         * it should however, be somehow related to relative and absolute values based on the available UTXOs.
         * @return basic tx fee per kB multiplied by txFeeFactor
         */
        fun minerFeeValue(txFeeLevel: TransactionFee, txFeeFactor: Float): Coin {
            val txFeeValue = when (txFeeLevel) {
                TransactionFee.LOW_PRIORITY -> 210000L  //FIXME those values probably shouldn't be hardcoded
                TransactionFee.ECONOMIC -> 240000L
                TransactionFee.NORMAL -> 360000L
                TransactionFee.PRIORITY -> 500000L
                else -> {
                    throw IllegalArgumentException("Unsupported fee $txFeeLevel")
                }
            } * txFeeFactor
            return Coin.valueOf(txFeeValue.toLong())
        }
    }
}
