package dev.keyvector.connector.sink;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;

/** Plain Java values to and from the SDK's {@link Document}. */
public final class Documents {

    private Documents() {
    }

    public static Document of(Object v) {
        if (v == null) {
            return Document.fromNull();
        }
        if (v instanceof String) {
            return Document.fromString((String) v);
        }
        if (v instanceof Boolean) {
            return Document.fromBoolean((Boolean) v);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return Document.fromNumber(((Number) v).longValue());
        }
        if (v instanceof BigDecimal) {
            return Document.fromNumber((BigDecimal) v);
        }
        if (v instanceof BigInteger) {
            return Document.fromNumber((BigInteger) v);
        }
        if (v instanceof Number) {
            return Document.fromNumber(((Number) v).doubleValue());
        }
        if (v instanceof List) {
            List<Document> out = new ArrayList<>();
            for (Object e : (List<?>) v) {
                out.add(of(e));
            }
            return Document.fromList(out);
        }
        if (v instanceof Map) {
            Map<String, Document> out = new LinkedHashMap<>();
            ((Map<?, ?>) v).forEach((k, val) -> out.put(String.valueOf(k), of(val)));
            return Document.fromMap(out);
        }
        return Document.fromString(String.valueOf(v));
    }

    public static Object toJava(Document d) {
        if (d == null || d.isNull()) {
            return null;
        }
        if (d.isString()) {
            return d.asString();
        }
        if (d.isBoolean()) {
            return d.asBoolean();
        }
        if (d.isNumber()) {
            SdkNumber n = d.asNumber();
            BigDecimal b = n.bigDecimalValue();
            return b.scale() <= 0 || b.stripTrailingZeros().scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        }
        if (d.isList()) {
            List<Object> out = new ArrayList<>();
            d.asList().forEach(e -> out.add(toJava(e)));
            return out;
        }
        if (d.isMap()) {
            Map<String, Object> out = new LinkedHashMap<>();
            d.asMap().forEach((k, v) -> out.put(k, toJava(v)));
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Document d) {
        Object o = toJava(d);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
