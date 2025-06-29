package com.nemesis.droidcrypt;

import org.bouncycastle.crypto.generators.SCrypt;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public class CryptUtils {
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;

    public static byte[] encrypt(byte[] data, String password) throws Exception {
        byte[] salt = generateSalt();
        SecretKeySpec key = generateKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(data);
        byte[] result = concatenateArrays(salt, iv, cipherText);
        clearSensitiveData(salt, iv, cipherText);
        return result;
    }

    public static byte[] decrypt(byte[] encryptedData, String password) throws Exception {
        byte[] salt = Arrays.copyOfRange(encryptedData, 0, SALT_LENGTH);
        byte[] iv = Arrays.copyOfRange(encryptedData, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(encryptedData, SALT_LENGTH + IV_LENGTH, encryptedData.length);
        SecretKeySpec key = generateKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] decryptedData = cipher.doFinal(cipherText);
        clearSensitiveData(salt, iv, cipherText, decryptedData);
        return decryptedData;
    }

    private static SecretKeySpec generateKey(String password, byte[] salt) throws Exception {
        byte[] key = SCrypt.generate(password.getBytes("UTF-8"), salt, 16384, 8, 1, 32);
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        Arrays.fill(key, (byte) 0);
        return secretKey;
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
}
