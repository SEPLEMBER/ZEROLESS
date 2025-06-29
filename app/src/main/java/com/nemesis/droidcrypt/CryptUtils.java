package com.nemesis.droidcrypt;

import org.bouncycastle.crypto.generators.SCrypt;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public class CryptUtils {
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int HMAC_LENGTH = 32; // SHA-256 HMAC

    public static byte[] encrypt(byte[] data, char[] password) throws Exception {
        byte[] salt = generateSalt();
        SecretKeySpec key = generateKey(password, salt);
        SecretKeySpec hmacKey = generateHmacKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(data);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(hmacKey);
        byte[] hmacValue = hmac.doFinal(cipherText);
        byte[] result = concatenateArrays(salt, iv, hmacValue, cipherText);
        clearSensitiveData(salt, iv, cipherText, hmacValue);
        clearKey(key);
        clearKey(hmacKey);
        return result;
    }

    public static byte[] decrypt(byte[] encryptedData, char[] password) throws Exception {
        byte[] salt = Arrays.copyOfRange(encryptedData, 0, SALT_LENGTH);
        byte[] iv = Arrays.copyOfRange(encryptedData, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
        byte[] hmacValue = Arrays.copyOfRange(encryptedData, SALT_LENGTH + IV_LENGTH, SALT_LENGTH + IV_LENGTH + HMAC_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(encryptedData, SALT_LENGTH + IV_LENGTH + HMAC_LENGTH, encryptedData.length);
        SecretKeySpec hmacKey = generateHmacKey(password, salt);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(hmacKey);
        byte[] computedHmac = hmac.doFinal(cipherText);
        if (!Arrays.equals(hmacValue, computedHmac)) {
            throw new SecurityException("Data integrity check failed");
        }
        SecretKeySpec key = generateKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] decryptedData = cipher.doFinal(cipherText);
        clearSensitiveData(salt, iv, hmacValue, cipherText, decryptedData);
        clearKey(key);
        clearKey(hmacKey);
        return decryptedData;
    }

    private static SecretKeySpec generateKey(char[] password, byte[] salt) throws Exception {
        byte[] key = SCrypt.generate(charToByteArray(password), salt, 8192, 8, 1, 32);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        Arrays.fill(key, (byte) 0);
        return keySpec;
    }

    private static SecretKeySpec generateHmacKey(char[] password, byte[] salt) throws Exception {
        byte[] key = SCrypt.generate(charToByteArray(password), salt, 8192, 8, 1, 32);
        SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
        Arrays.fill(key, (byte) 0);
        return keySpec;
    }

    private static byte[] charToByteArray(char[] chars) throws Exception {
        byte[] bytes = new String(chars).getBytes("UTF-8");
        Arrays.fill(chars, '\0'); // Clear char array
        return bytes;
    }

    private static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static byte[] concatenateArrays(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int currentIndex = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, currentIndex, array.length);
            currentIndex += array.length;
        }
        return result;
    }

    private static void clearSensitiveData(byte[]... arrays) {
        for (byte[] array : arrays) {
            if (array != null) {
                Arrays.fill(array, (byte) 0);
            }
        }
        System.gc();
    }

    private static void clearKey(SecretKeySpec key) {
        if (key != null) {
            Arrays.fill(key.getEncoded(), (byte) 0);
        }
    }
}
