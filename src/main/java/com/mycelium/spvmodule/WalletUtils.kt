package com.mycelium.spvmodule

import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.TypefaceSpan

import com.google.common.base.Charsets
import org.bitcoinj.core.*

import org.bitcoinj.wallet.KeyChainGroup
import org.bitcoinj.wallet.UnreadableWalletException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileFilter
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Writer
import java.text.ParseException
import java.util.Date
import java.util.LinkedList

object WalletUtils {
    fun formatAddress(address: Address, groupSize: Int, lineSize: Int): Editable {
        return formatHash(address.toBase58(), groupSize, lineSize)
    }

    fun formatAddress(prefix: String?, address: Address, groupSize: Int, lineSize: Int): Editable {
        return formatHash(prefix, address.toBase58(), groupSize, lineSize, Constants.CHAR_THIN_SPACE)
    }

    fun formatHash(address: String, groupSize: Int, lineSize: Int): Editable {
        return formatHash(null, address, groupSize, lineSize, Constants.CHAR_THIN_SPACE)
    }

    fun longHash(hash: Sha256Hash): Long {
        //TODO: this needs unit testing. the kotlin conversion required some quick fixing.
        val bytes = hash.bytes.map(Byte::toLong)
        return bytes[31] and 0xFFL or (bytes[30] and 0xFFL shl 8) or (bytes[29] and 0xFFL shl 16) or (bytes[28] and 0xFFL shl 24) or (bytes[27] and 0xFFL shl 32) or (bytes[26] and 0xFFL shl 40) or (bytes[25] and 0xFFL shl 48) or (bytes[23] and 0xFFL shl 56)
    }

    fun formatHash(prefix: String?, address: String, groupSize: Int, lineSize: Int,
                   groupSeparator: Char): Editable {
        val builder = if (prefix != null) SpannableStringBuilder(prefix) else SpannableStringBuilder()

        val len = address.length
        var i = 0
        while (i < len) {
            val end = i + groupSize
            val part = address.substring(i, if (end < len) end else len)

            builder.append(part)
            builder.setSpan(TypefaceSpan("monospace"), builder.length - part.length, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (end < len) {
                val endOfLine = lineSize > 0 && end % lineSize == 0
                builder.append(if (endOfLine) '\n' else groupSeparator)
            }
            i += groupSize
        }

        return builder
    }

    fun getToAddressOfSent(tx: Transaction, wallet: Wallet): Address? {
        for (output in tx.outputs) {
                if (!output.isMine(wallet)) {
                    val script = output.scriptPubKey
                    return script.getToAddress(Constants.NETWORK_PARAMETERS, true)
                }

        }

        return null
    }

    fun getWalletAddressOfReceived(tx: Transaction, wallet: Wallet): Address? {
        for (output in tx.outputs) {
                if (output.isMine(wallet)) {
                    val script = output.scriptPubKey
                    return script.getToAddress(Constants.NETWORK_PARAMETERS, true)
                }
        }

        return null
    }

    @Throws(IOException::class)
    fun restoreWalletFromProtobufOrBase58(`is`: InputStream, expectedNetworkParameters: NetworkParameters): Wallet {
        `is`.mark(Constants.BACKUP_MAX_CHARS.toInt())

        try {
            return restoreWalletFromProtobuf(`is`, expectedNetworkParameters)
        } catch (x: IOException) {
            try {
                `is`.reset()
                return restorePrivateKeysFromBase58(`is`, expectedNetworkParameters)
            } catch (x2: IOException) {
                throw IOException("cannot read protobuf (" + x.message + ") or base58 (" + x2.message + ")", x)
            }

        }

    }

    @Throws(IOException::class)
    fun restoreWalletFromProtobuf(`is`: InputStream, expectedNetworkParameters: NetworkParameters): Wallet {
        try {
            val wallet = WalletProtobufSerializer().readWallet(`is`, true, null)

            if (wallet.params != expectedNetworkParameters)
                throw IOException("bad wallet backup network parameters: " + wallet.params.id)
            if (!wallet.isConsistent)
                throw IOException("inconsistent wallet backup")

            return wallet
        } catch (x: UnreadableWalletException) {
            throw IOException("unreadable wallet", x)
        }

    }

    @Throws(IOException::class)
    fun restorePrivateKeysFromBase58(`is`: InputStream, expectedNetworkParameters: NetworkParameters): Wallet {
        val keyReader = BufferedReader(InputStreamReader(`is`, Charsets.UTF_8))

        // create non-HD wallet
        val group = KeyChainGroup(expectedNetworkParameters)
        group.importKeys(WalletUtils.readKeys(keyReader, expectedNetworkParameters))
        return Wallet(expectedNetworkParameters, group)
    }

    @Throws(IOException::class)
    fun writeKeys(out: Writer, keys: List<ECKey>) {
        val format = Iso8601Format.newDateTimeFormatT()

        out.write("# KEEP YOUR PRIVATE KEYS SAFE! Anyone who can read this can spend your Bitcoins.\n")

        for (key in keys) {
            out.write(key.getPrivateKeyEncoded(Constants.NETWORK_PARAMETERS).toBase58())
            if (key.creationTimeSeconds != 0L) {
                out.write(" ")
                out.write(format.format(Date(key.creationTimeSeconds * DateUtils.SECOND_IN_MILLIS)))
            }
            out.write("\n")
        }
    }

    @Throws(IOException::class)
    fun readKeys(`in`: BufferedReader, expectedNetworkParameters: NetworkParameters): List<ECKey> {
        try {
            val format = Iso8601Format.newDateTimeFormatT()

            val keys = LinkedList<ECKey>()

            var charCount: Long = 0
            while (true) {
                val line = `in`.readLine() ?: break
// eof
                charCount += line.length.toLong()
                if (charCount > Constants.BACKUP_MAX_CHARS)
                    throw IOException("read more than the limit of " + Constants.BACKUP_MAX_CHARS + " characters")
                if (line.trim { it <= ' ' }.isEmpty() || line[0] == '#')
                    continue // skip comment

                val parts = line.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

                val key = DumpedPrivateKey.fromBase58(expectedNetworkParameters, parts[0]).key
                key.creationTimeSeconds = if (parts.size >= 2) format.parse(parts[1]).time / DateUtils.SECOND_IN_MILLIS else 0

                keys.add(key)
            }

            return keys
        } catch (x: AddressFormatException) {
            throw IOException("cannot read keys", x)
        } catch (x: ParseException) {
            throw IOException("cannot read keys", x)
        }

    }

    val KEYS_FILE_FILTER: FileFilter = FileFilter { file ->
        var reader: BufferedReader? = null

        try {
            reader = BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8))
            WalletUtils.readKeys(reader, Constants.NETWORK_PARAMETERS)

            return@FileFilter true
        } catch (x: IOException) {
            return@FileFilter false
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (ignore: IOException) {
                }

            }
        }
    }

    val BACKUP_FILE_FILTER: FileFilter = FileFilter { file ->
        var `is`: InputStream? = null

        try {
            `is` = FileInputStream(file)
            return@FileFilter WalletProtobufSerializer.isWallet(`is`)
        } catch (x: IOException) {
            return@FileFilter false
        } finally {
            if (`is` != null) {
                try {
                    `is`.close()
                } catch (ignore: IOException) {
                }

            }
        }
    }

    fun walletToByteArray(wallet: Wallet): ByteArray {
        try {
            val os = ByteArrayOutputStream()
            WalletProtobufSerializer().writeWallet(wallet, os)
            os.close()
            return os.toByteArray()
        } catch (x: IOException) {
            throw RuntimeException(x)
        }

    }

    @Throws(IOException::class)
    fun walletFromByteArray(walletBytes: ByteArray): Wallet {
        try {
            val `is` = ByteArrayInputStream(walletBytes)
            val wallet = WalletProtobufSerializer().readWallet(`is`)
            `is`.close()
            return wallet
        } catch (x: UnreadableWalletException) {
            throw RuntimeException(x)
        }

    }
}
