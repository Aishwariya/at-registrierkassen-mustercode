/*
 * Copyright (C) 2015
 * A-SIT Plus GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package at.asitplus.regkassen.core.base.util;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x9.X9IntegerConverter;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.*;

/**
 * Util class for AES encryption and decryption with different modes of operation
 */
public class CryptoUtil {

    /**
     * Helper method to convert DER-encoded signature values (e.g. used by Java) to concatenated signature values
     * (as used by the JWS-standard)
     *
     * @param derEncodedSignatureValue DER-encoded signature value
     * @return concatenated signature value (as used by JWS standard)
     * @throws IOException
     */
    public static byte[] convertDEREncodedSignatureToJWSConcatenated(byte[] derEncodedSignatureValue) throws IOException {
        ASN1InputStream asn1InputStream = new ASN1InputStream(derEncodedSignatureValue);
        ASN1Primitive asn1Primitive = asn1InputStream.readObject();
        ASN1Sequence asn1Sequence = (ASN1Sequence.getInstance(asn1Primitive));
        ASN1Integer rASN1 = (ASN1Integer) asn1Sequence.getObjectAt(0);
        ASN1Integer sASN1 = (ASN1Integer) asn1Sequence.getObjectAt(1);
        X9IntegerConverter x9IntegerConverter = new X9IntegerConverter();
        byte[] r = x9IntegerConverter.integerToBytes(rASN1.getValue(), 32);
        byte[] s = x9IntegerConverter.integerToBytes(sASN1.getValue(), 32);

        byte[] concatenatedSignatureValue = new byte[64];
        System.arraycopy(r, 0, concatenatedSignatureValue, 0, 32);
        System.arraycopy(s, 0, concatenatedSignatureValue, 32, 32);

        return concatenatedSignatureValue;
    }

    /**
     * Generates a random AES key for encrypting/decrypting the turnover value
     * ATTENTION: In a real cash box this key would be generated during the init process and stored in a secure area
     *
     * @return generated AES key
     */
    public static SecretKey createAESKey() {
        try {
            KeyGenerator kgen = KeyGenerator.getInstance("AES");
            int keySize = 256;
            kgen.init(keySize);
            return kgen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * helper method to check whether the JVM has the unlimited strength policy installed
     *
     * @return
     */
    public static boolean isUnlimitedStrengthPolicyAvailable() {
        try {
            return Cipher.getMaxAllowedKeyLength("AES") >= 256;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * convert base64 encoded AES key to JAVA SecretKey
     *
     * @param base64AESKey BASE64 encoded AES key
     * @return Java SecretKey representation of encoded AES key
     */
    public static SecretKey convertBase64KeyToSecretKey(String base64AESKey) {
        byte[] rawAesKey = CashBoxUtils.base64Decode(base64AESKey, false);
        SecretKeySpec aesKey = new SecretKeySpec(rawAesKey, "AES");
        return aesKey;
    }

    /**
     * method for AES encryption in ECB mode
     *
     * @param concatenatedHashValue
     * @param turnoverCounter
     * @param symmetricKey
     */
    public static String encryptECB(byte[] concatenatedHashValue, Long turnoverCounter, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        // prepare data
        // here, 8 bytes are used for the turnover counter (more then enough for
        // every possible turnover...), however
        // the specification only requires 5 bytes at a minimum
        // bytes 0-7 are used for the turnover counter, which is represented by
        // 8-byte
        // two-complement, Big Endian representation (equal to Java LONG), bytes
        // 8-15 are set to 0
        // negative values are possible (very rare)
        ByteBuffer byteBufferData = ByteBuffer.allocate(16);
        byteBufferData.putLong(turnoverCounter);
        byte[] data = byteBufferData.array();

        // prepare AES cipher with ECB mode, NoPadding is essential for the
        // decryption process. Padding could not be reconstructed due
        // to storing only 8 bytes of the cipher text (not the full 16 bytes)
        // (or 5 bytes if the mininum turnover length is used)
        //
        // Note: Due to the use of ECB mode, no IV is defined for initializing
        // the cipher. In addition, the data is not enciphered directly. Instead,
        // the computed IV is encrypted. The result is subsequently XORed
        // bitwise with the data to compute the cipher text.
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, symmetricKey);
        byte[] intermediateResult = cipher.doFinal(IV);

        byte[] result = new byte[data.length];

        // xor encryption result with data
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (((int) data[i]) ^ ((int) intermediateResult[i]));
        }

        byte[] encryptedTurnOverValue = new byte[8];

        // turnover length is used
        System.arraycopy(result, 0, encryptedTurnOverValue, 0, encryptedTurnOverValue.length);

        // encode result as BASE64
        return CashBoxUtils.base64Encode(encryptedTurnOverValue, false);
    }

    /**
     * method for AES decryption in ECB mode
     *
     * @param concatenatedHashValue
     * @param base64EncryptedTurnOverValue
     * @param symmetricKey
     */
    public static long decryptECB(byte[] concatenatedHashValue, String base64EncryptedTurnOverValue, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        byte[] encryptedTurnOverValue = CashBoxUtils.base64Decode(base64EncryptedTurnOverValue, false);
        int lengthOfEncryptedTurnOverValue = encryptedTurnOverValue.length;

        // prepare AES cipher with ECB mode
        //
        // Note: Due to the use of ECB mode, no IV is defined for initializing
        // the cipher. In addition, the data is not enciphered directly. Instead,
        // the IV computed above is encrypted again. The result is subsequently XORed
        // bitwise with the cipher text to retrieve the plain data.
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, symmetricKey);
        byte[] intermediateResult = cipher.doFinal(IV);

        byte[] result = new byte[encryptedTurnOverValue.length];

        // XOR decryption result with data
        for (int i = 0; i < encryptedTurnOverValue.length; i++) {
            result[i] = (byte) (((int) encryptedTurnOverValue[i]) ^ ((int) intermediateResult[i]));
        }

        // extract relevant bytes from decryption result
        byte[] testPlainTurnOverValue = new byte[lengthOfEncryptedTurnOverValue];
        System.arraycopy(result, 0, testPlainTurnOverValue, 0, lengthOfEncryptedTurnOverValue);

        // create java LONG out of ByteArray
        ByteBuffer testPlainTurnOverValueByteBuffer = ByteBuffer.wrap(testPlainTurnOverValue);

        return testPlainTurnOverValueByteBuffer.getLong();

    }

    /**
     * method for AES encryption in CFB mode (for the first block CFB and CTR are exactly the same
     *
     * @param concatenatedHashValue
     * @param turnoverCounter
     * @param symmetricKey
     */
    public static String encryptCFB(byte[] concatenatedHashValue, Long turnoverCounter, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        // prepare data
        // here, 8 bytes are used for the turnover counter (more then enough for
        // every possible turnover...), however
        // the specification only requires 5 bytes at a minimum
        // bytes 0-7 are used for the turnover counter, which is represented by
        // 8-byte
        // two-complement, Big Endian representation (equal to Java LONG), bytes
        // 8-15 are set to 0
        // negative values are possible (very rare)
        ByteBuffer byteBufferData = ByteBuffer.allocate(16);
        byteBufferData.putLong(turnoverCounter);
        byte[] data = byteBufferData.array();

        // prepare AES cipher with CFB mode, NoPadding is essential for the
        // decryption process. Padding could not be reconstructed due
        // to storing only 8 bytes of the cipher text (not the full 16 bytes)
        // (or 5 bytes if the mininum turnover length is used)
        IvParameterSpec ivSpec = new IvParameterSpec(IV);

        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, symmetricKey, ivSpec);

        // encrypt the turnover value with the prepared cipher
        byte[] encryptedTurnOverValueComplete = cipher.doFinal(data);

        // extract bytes that will be stored in the receipt (only bytes 0-7)
        byte[] encryptedTurnOverValue = new byte[8]; // or 5 bytes if min.
        // turnover length is
        // used
        System.arraycopy(encryptedTurnOverValueComplete, 0, encryptedTurnOverValue, 0, encryptedTurnOverValue.length);

        // encode result as BASE64
        String base64EncryptedTurnOverValue = CashBoxUtils.base64Encode(encryptedTurnOverValue, false);

        return base64EncryptedTurnOverValue;

    }

    /**
     * method for AES decryption in CFB mode
     *
     * @param concatenatedHashValue
     * @param base64EncryptedTurnOverValue
     * @param symmetricKey
     */
    public static long decryptCFB(byte[] concatenatedHashValue, String base64EncryptedTurnOverValue, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        byte[] encryptedTurnOverValue = CashBoxUtils.base64Decode(base64EncryptedTurnOverValue, false);
        int lengthOfEncryptedTurnOverValue = encryptedTurnOverValue.length;

        // prepare AES cipher with CFB mode
        IvParameterSpec ivSpec = new IvParameterSpec(IV);

        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, symmetricKey, ivSpec);
        byte[] testPlainTurnOverValueComplete = cipher.doFinal(encryptedTurnOverValue);

        // extract relevant bytes from decryption result
        byte[] testPlainTurnOverValue = new byte[lengthOfEncryptedTurnOverValue];
        System.arraycopy(testPlainTurnOverValueComplete, 0, testPlainTurnOverValue, 0, lengthOfEncryptedTurnOverValue);

        // create java LONG out of ByteArray
        ByteBuffer testPlainTurnOverValueByteBuffer = ByteBuffer.wrap(testPlainTurnOverValue);

        return testPlainTurnOverValueByteBuffer.getLong();

    }

    /**
     * method for AES encryption in CTR mode
     *
     * @param concatenatedHashValue
     * @param turnoverCounter
     * @param symmetricKey
     */
    public static String encryptCTR(byte[] concatenatedHashValue, Long turnoverCounter, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        // prepare data
        // here, 8 bytes are used for the turnover counter (more then enough for
        // every possible turnover...), however
        // the specification only requires 5 bytes at a minimum
        // bytes 0-7 are used for the turnover counter, which is represented by
        // 8-byte
        // two-complement, Big Endian representation (equal to Java LONG), bytes
        // 8-15 are set to 0
        // negative values are possible (very rare)
        ByteBuffer byteBufferData = ByteBuffer.allocate(16);
        byteBufferData.putLong(turnoverCounter);
        byte[] data = byteBufferData.array();

        // prepare AES cipher with CTR/ICM mode, NoPadding is essential for the
        // decryption process. Padding could not be reconstructed due
        // to storing only 8 bytes of the cipher text (not the full 16 bytes)
        // (or 5 bytes if the mininum turnover length is used)
        IvParameterSpec ivSpec = new IvParameterSpec(IV);

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, symmetricKey, ivSpec);

        // encrypt the turnover value with the prepared cipher
        byte[] encryptedTurnOverValueComplete = cipher.doFinal(data);

        // extract bytes that will be stored in the receipt (only bytes 0-7)
        // cryptographic NOTE: this is only possible due to the use of the CTR
        // mode, would not work for ECB/CBC etc. modes
        byte[] encryptedTurnOverValue = new byte[8]; // or 5 bytes if min.
        // turnover length is
        // used
        System.arraycopy(encryptedTurnOverValueComplete, 0, encryptedTurnOverValue, 0, encryptedTurnOverValue.length);

        // encode result as BASE64

        return CashBoxUtils.base64Encode(encryptedTurnOverValue, false);

    }

    /**
     * method for AES decryption in CTR mode
     *
     * @param concatenatedHashValue
     * @param base64EncryptedTurnOverValue
     * @param symmetricKey
     */
    public static long decryptCTR(byte[] concatenatedHashValue, String base64EncryptedTurnOverValue, SecretKey symmetricKey)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        // extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);
        byte[] IV = byteBufferIV.array();

        byte[] encryptedTurnOverValue = CashBoxUtils.base64Decode(base64EncryptedTurnOverValue, false);
        int lengthOfEncryptedTurnOverValue = encryptedTurnOverValue.length;

        // prepare AES cipher with CTR/ICM mode
        IvParameterSpec ivSpec = new IvParameterSpec(IV);

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, symmetricKey, ivSpec);
        byte[] testPlainTurnOverValueComplete = cipher.doFinal(encryptedTurnOverValue);

        // extract relevant bytes from decryption result
        byte[] testPlainTurnOverValue = new byte[lengthOfEncryptedTurnOverValue];
        System.arraycopy(testPlainTurnOverValueComplete, 0, testPlainTurnOverValue, 0, lengthOfEncryptedTurnOverValue);

        // create java LONG out of ByteArray
        ByteBuffer testPlainTurnOverValueByteBuffer = ByteBuffer.wrap(testPlainTurnOverValue);
        return testPlainTurnOverValueByteBuffer.getLong();

    }

    //see next method
    public static long decryptTurnOverCounter(String encryptedTurnOverCounterBase64, String hashAlgorithm, String cashBoxIDUTF8String, String receiptIdentifierUTF8String, String aesKeyBase64) throws Exception {
        byte[] rawAesKey = CashBoxUtils.base64Decode(aesKeyBase64, false);
        SecretKey aesKey = new SecretKeySpec(rawAesKey, "AES");
        return decryptTurnOverCounter(encryptedTurnOverCounterBase64, hashAlgorithm, cashBoxIDUTF8String, receiptIdentifierUTF8String, aesKey);
    }

    /**
     * decrypt the turnover counter with the given AES key, and parameters for IV creation
     * Ref: Detailspezifikation Abs 8/Abs 9/Abs 10
     *
     * @param encryptedTurnOverCounterBase64 encrypted turnover counter
     * @param hashAlgorithm                  hash-algorithm used to generate IV
     * @param cashBoxIDUTF8String            cashbox-id, required for IV creation
     * @param receiptIdentifierUTF8String    receiptidentifier, required for IV creation
     * @param aesKey                         aes key
     * @return decrypted turnover value as long
     * @throws Exception
     */
    public static long decryptTurnOverCounter(String encryptedTurnOverCounterBase64, String hashAlgorithm, String cashBoxIDUTF8String, String receiptIdentifierUTF8String, SecretKey aesKey) throws Exception {
        //calc IV value (cashbox if + receipt identifer, both as UTF-8 Strings)
        String IVUTF8StringRepresentation = cashBoxIDUTF8String + receiptIdentifierUTF8String;

        //calc hash
        MessageDigest messageDigest = MessageDigest.getInstance(hashAlgorithm);
        byte[] hashValue = messageDigest.digest(IVUTF8StringRepresentation.getBytes());
        byte[] concatenatedHashValue = new byte[16];
        System.arraycopy(hashValue, 0, concatenatedHashValue, 0, 16);

        //extract bytes 0-15 from hash value
        ByteBuffer byteBufferIV = ByteBuffer.allocate(16);
        byteBufferIV.put(concatenatedHashValue);

        //IV for AES algorithm
        byte[] IV = byteBufferIV.array();

        //prepare AES cipher with CTR/ICM mode, NoPadding is essential for the decryption process. Padding could not be reconstructed due
        //to storing only 8 bytes of the cipher text (not the full 16 bytes) (or 5 bytes if the minimum turnover length is used)
        IvParameterSpec ivSpec = new IvParameterSpec(IV);

        //start decryption process
        ByteBuffer encryptedTurnOverValueComplete = ByteBuffer.allocate(16);

        //decode turnover base64 value
        byte[] encryptedTurnOverValue = CashBoxUtils.base64Decode(encryptedTurnOverCounterBase64, false);

        //extract length (required to extract the correct number of bytes from decrypted value
        int lengthOfEncryptedTurnOverValue = encryptedTurnOverValue.length;

        //prepare for decryption (require 128 bit blocks...)
        encryptedTurnOverValueComplete.put(encryptedTurnOverValue);

        //decryption setup, AES ciper in CTR mode, NO PADDING!)
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec);

        //decrypt value, now we have a 128 bit block, with trailing junk bytes
        byte[] testPlainTurnOverValueComplete = cipher.doFinal(encryptedTurnOverValue);

        //remove junk bytes by extracting known length of plain text
        byte[] testPlainTurnOverValue = new byte[lengthOfEncryptedTurnOverValue];
        System.arraycopy(testPlainTurnOverValueComplete, 0, testPlainTurnOverValue, 0, lengthOfEncryptedTurnOverValue);

        //create java LONG out of ByteArray
        ByteBuffer plainTurnOverValueByteBuffer = ByteBuffer.wrap(testPlainTurnOverValue);

        return plainTurnOverValueByteBuffer.getLong();
    }
}
