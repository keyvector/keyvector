package dev.keyvector.connector.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.UserDefinedType;

import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCell;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCellMapDefinition;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCellValue;

/**
 * One representation of a CQL value, whichever way it arrived.
 *
 * <p>A row reaches Keyvector two ways: as a CDC record (every scalar a string) and as a CQL
 * row read by the backfill (typed Java objects). The vector key is built from the primary
 * key's values, so the two must normalize to exactly the same thing - otherwise a backfilled
 * row and a later update to it get different keys, and the index quietly holds both.
 *
 * <p>Normal forms: every integer type is a {@link Long}, {@code varint} a {@link BigInteger},
 * {@code decimal} a {@link BigDecimal}, {@code float}/{@code double} a {@link Double},
 * {@code timestamp} an {@link Instant}, {@code date} a {@link LocalDate}, {@code uuid} and
 * {@code timeuuid} a {@link UUID}, {@code blob} a {@code byte[]}, {@code inet} and {@code time}
 * a String, collections and tuples a {@link List}, maps and UDTs a {@link LinkedHashMap}.
 */
public final class Values {

    private Values() {
    }

    // ---- From a CDC record -------------------------------------------------------------

    public static Object fromStream(KeyspacesCell cell) {
        return cell == null ? null : fromStream(cell.value());
    }

    public static Object fromStream(KeyspacesCellValue v) {
        if (v == null || v.type() == null) {
            return null;
        }
        switch (v.type()) {
            case TEXTT: return v.textT();
            case VARCHART: return v.varcharT();
            case ASCIIT: return v.asciiT();
            case INETT: return v.inetT();
            case TIMET: return v.timeT();
            case INTT: return parseLong(v.intT());
            case SMALLINTT: return parseLong(v.smallintT());
            case TINYINTT: return parseLong(v.tinyintT());
            case BIGINTT: return parseLong(v.bigintT());
            case COUNTERT: return parseLong(v.counterT());
            case VARINTT: return v.varintT() == null ? null : new BigInteger(v.varintT().trim());
            case DECIMALT: return v.decimalT() == null ? null : new BigDecimal(v.decimalT().trim());
            case FLOATT: return v.floatT() == null ? null : Double.parseDouble(v.floatT().trim());
            case DOUBLET: return v.doubleT() == null ? null : Double.parseDouble(v.doubleT().trim());
            case BOOLT: return v.boolT();
            case TIMESTAMPT: return parseTimestamp(v.timestampT());
            case DATET: return parseDate(v.dateT());
            case UUIDT: return v.uuidT() == null ? null : UUID.fromString(v.uuidT().trim());
            case TIMEUUIDT: return v.timeuuidT() == null ? null : UUID.fromString(v.timeuuidT().trim());
            case BLOBT: return v.blobT() == null ? null : v.blobT().asByteArray();
            case LISTT: return cells(v.listT());
            case SETT: return cells(v.setT());
            case TUPLET: return cells(v.tupleT());
            case MAPT: {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (KeyspacesCellMapDefinition e : v.mapT()) {
                    out.put(fromStream(e.key()), fromStream(e.value()));
                }
                return out;
            }
            case UDTT: {
                Map<Object, Object> out = new LinkedHashMap<>();
                v.udtT().forEach((name, cell) -> out.put(name, fromStream(cell)));
                return out;
            }
            default:
                return v.textT();
        }
    }

    private static List<Object> cells(List<KeyspacesCell> cells) {
        List<Object> out = new ArrayList<>(cells == null ? 0 : cells.size());
        if (cells != null) {
            for (KeyspacesCell c : cells) {
                out.add(fromStream(c));
            }
        }
        return out;
    }

    private static Long parseLong(String s) {
        return s == null ? null : Long.parseLong(s.trim());
    }

    private static final DateTimeFormatter CQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S][XXX][XX][X]");

    /**
     * The CDC stream's timestamp text format is not pinned down in AWS's documentation, so
     * accept every form CQL itself prints or accepts: epoch milliseconds, ISO-8601 and the
     * {@code yyyy-MM-dd HH:mm:ss.SSSZ} literal form.
     */
    static Instant parseTimestamp(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.matches("-?\\d+")) {
            return Instant.ofEpochMilli(Long.parseLong(t));
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(t, CQL_TIMESTAMP).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unrecognized timestamp: " + s, e);
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.matches("\\d+")) {
            // CQL's wire form: unsigned days with the epoch at 2^31.
            return LocalDate.ofEpochDay(Long.parseLong(t) - (1L << 31));
        }
        return LocalDate.parse(t);
    }

    // ---- From a CQL row (the backfill) -------------------------------------------------

    public static Object fromDriver(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof Float) {
            return Double.parseDouble(o.toString());
        }
        if (o instanceof InetAddress) {
            return ((InetAddress) o).getHostAddress();
        }
        if (o instanceof LocalTime) {
            return o.toString();
        }
        if (o instanceof ByteBuffer) {
            ByteBuffer b = ((ByteBuffer) o).duplicate();
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        }
        if (o instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object e : (Collection<?>) o) {
                out.add(fromDriver(e));
            }
            return out;
        }
        if (o instanceof Map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            ((Map<?, ?>) o).forEach((k, val) -> out.put(fromDriver(k), fromDriver(val)));
            return out;
        }
        if (o instanceof UdtValue) {
            UdtValue u = (UdtValue) o;
            UserDefinedType type = u.getType();
            Map<Object, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < u.size(); i++) {
                out.put(type.getFieldNames().get(i).asInternal(), fromDriver(u.getObject(i)));
            }
            return out;
        }
        if (o instanceof TupleValue) {
            TupleValue t = (TupleValue) o;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < t.size(); i++) {
                out.add(fromDriver(t.getObject(i)));
            }
            return out;
        }
        return o;
    }

    // ---- Text forms ---------------------------------------------------------------------

    /** The form a value takes inside a vector key. Must be identical for both sources. */
    public static String keyText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Instant) {
            return Long.toString(((Instant) v).toEpochMilli());
        }
        if (v instanceof byte[]) {
            return "0x" + HexFormat.of().formatHex((byte[]) v);
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toString();
        }
        return String.valueOf(v);
    }

    /** The form a value takes when it is written into text that will be embedded. */
    public static String displayText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof byte[]) {
            return "";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object e : (List<?>) v) {
                String s = displayText(e);
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String s = displayText(e.getValue());
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(displayText(e.getKey())).append(": ").append(s);
                }
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }
}
