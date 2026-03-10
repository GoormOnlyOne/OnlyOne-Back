package com.example.onlyone.global.common.util;


import java.nio.ByteBuffer;
import java.util.UUID;

public class UuidUtils {
    // UUID -> 16 bytes
    public static byte[] toBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    // 16 bytes -> UUID
    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long msb = bb.getLong();
        long lsb = bb.getLong();
        return new UUID(msb, lsb);
    }

    // "xxxxxxxx-xxxx-...." 문자열 -> 16 bytes (유효성 포함)
    public static byte[] fromStringToBytes(String s) {
        return toBytes(UUID.fromString(s));
    }
}