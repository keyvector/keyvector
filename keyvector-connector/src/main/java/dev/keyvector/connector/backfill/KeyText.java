package dev.keyvector.connector.backfill;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.UUID;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.protocol.internal.ProtocolConstants;

/**
 * Reads a primary key value back from its {@link dev.keyvector.connector.model.Values#keyText}
 * form into the Java type the driver binds for that column. The inverse of the key encoding.
 */
public final class KeyText {

    private KeyText() {
    }

    public static Object parse(String text, DataType type) {
        switch (type.getProtocolCode()) {
            case ProtocolConstants.DataType.ASCII:
            case ProtocolConstants.DataType.VARCHAR:
                return text;
            case ProtocolConstants.DataType.INT: return Integer.parseInt(text);
            case ProtocolConstants.DataType.BIGINT:
            case ProtocolConstants.DataType.COUNTER: return Long.parseLong(text);
            case ProtocolConstants.DataType.SMALLINT: return Short.parseShort(text);
            case ProtocolConstants.DataType.TINYINT: return Byte.parseByte(text);
            case ProtocolConstants.DataType.VARINT: return new BigInteger(text);
            case ProtocolConstants.DataType.DECIMAL: return new BigDecimal(text);
            case ProtocolConstants.DataType.DOUBLE: return Double.parseDouble(text);
            case ProtocolConstants.DataType.FLOAT: return Float.parseFloat(text);
            case ProtocolConstants.DataType.BOOLEAN: return Boolean.parseBoolean(text);
            case ProtocolConstants.DataType.TIMESTAMP: return Instant.ofEpochMilli(Long.parseLong(text));
            case ProtocolConstants.DataType.DATE: return LocalDate.parse(text);
            case ProtocolConstants.DataType.TIME: return LocalTime.parse(text);
            case ProtocolConstants.DataType.UUID:
            case ProtocolConstants.DataType.TIMEUUID: return UUID.fromString(text);
            case ProtocolConstants.DataType.BLOB:
                return ByteBuffer.wrap(HexFormat.of().parseHex(text.startsWith("0x") ? text.substring(2) : text));
            case ProtocolConstants.DataType.INET:
                try {
                    return InetAddress.getByName(text);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Not an address: " + text, e);
                }
            default:
                throw new IllegalArgumentException("Keyvector cannot read a primary key column of type " + type.asCql(true, true));
        }
    }
}
