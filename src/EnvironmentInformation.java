import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Arrays;

public class EnvironmentInformation {

    Map<String,String> configuration;
    String EnvironmentIdentifier;

    EnvironmentInformation(String EnvID)
    {
        EnvironmentIdentifier=EnvID;
        configuration=new LinkedHashMap<>();
    }
    boolean load(String filename)
    {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            String currentSection = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith(";")) {
                    continue; // skip empty lines and comments
                }

                if (line.startsWith("[")) {
                    // new section
                    int endIndex = line.indexOf("]");
                    if (endIndex > 0) {
                        currentSection = line.substring(1, endIndex);
                    }
                } else {
                    // key-value pair
                    int index = line.indexOf("=");
                    if (index > 0) {
                        String key = line.substring(0, index).trim();
                        String value = line.substring(index + 1).trim();

                        if (currentSection != null) {
                            key = currentSection + "." + key;
                        }

                        configuration.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

return true;
    }

    void save(String filename)
    {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            String section = "";
            Map<String, String> orderedConfiguration = new LinkedHashMap<>(configuration);
            for (Map.Entry<String, String> entry : orderedConfiguration.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                int dotIndex = key.indexOf('.');
                if (dotIndex > 0) {
                    String newSection = key.substring(0, dotIndex);
                    if (!newSection.equals(section)) {
                        writer.println("[" + newSection + "]");
                        section = newSection;
                    }
                    key = key.substring(dotIndex + 1);
                }
                writer.println(key + "=" + value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void setValue(String variablename, String value)
    {
        configuration.put(variablename,value);
    }


    String getValue(String variablename)
    {
        return configuration.get(variablename);
    }

    public String decodeValue(String variablename)
    {
        //System.out.println("EnvironmentInformation(" + Thread.currentThread().getId() + ").decodeValue(String variablename): Starting decode value for "+variablename);
        //System.out.println("EnvironmentInformation(" + Thread.currentThread().getId() + ").decodeValue(String variablename): configuration is "+configuration);
        String value=configuration.get(variablename);
        //System.out.println("EnvironmentInformation(" + Thread.currentThread().getId() + ").decodeValue(String variablename): value is "+value);

        byte[] encryptedvalue = Base64.getUrlDecoder().decode(value);
        String keyStorePassword="changeme";
        String keyAlias="myKey";
        //String keyStoreFile="\\cfg\\keystore";
        //String keyStoreFile=System.getProperty("user.dir") + File.separator + "cfg" + File.separator + "keystore";
        String jarlocation=Main.getJarLocation(Main.class);
        String keyStoreFile=jarlocation + File.separator + EnvironmentIdentifier+File.separator+"config"+File.separator+EnvironmentIdentifier+".keystore";
        String cipherAlgorithm = "AES/CBC/PKCS5Padding";

        String result="";
        try{
            result=decryptPassword(encryptedvalue,keyStoreFile,keyStorePassword,keyAlias,cipherAlgorithm);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        //System.out.println("EnvironmentInformation("+Thread.currentThread().getId()+").decodeValue(): Decrypted password: "+result);
        return result;
    }

    public static String decryptPassword(byte[] encryptedPassword, String keyStoreFile, String keyStorePassword, String keyAlias, String cipherAlgorithm) throws Exception {
        // Load key store
        KeyStore keyStore = KeyStore.getInstance("JCEKS");
        try (InputStream in = new FileInputStream(keyStoreFile)) {
            keyStore.load(in, keyStorePassword.toCharArray());
        }

        //System.out.println("contains MyKey: "+keyStore.containsAlias(keyAlias));
        //System.out.println("aliases(): "+keyStore.aliases());
        //System.out.println("Stored at: "+keyStore.getCreationDate(keyAlias));
        // Get cipher key from key store
        KeyStore.PasswordProtection password = new KeyStore.PasswordProtection(keyStorePassword.toCharArray());
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(keyAlias, password);
                SecretKey cipherKey = entry.getSecretKey();

        //System.out.println("SecretKey Algorithm: " + cipherKey.getAlgorithm());

        Cipher encryptCipher = Cipher.getInstance(cipherKey.getAlgorithm());
        //System.out.println("encryptedPassword: "+bytesToHex(encryptedPassword));

        byte[] decryptedIv = Arrays.copyOfRange(encryptedPassword, 0, encryptCipher.getBlockSize());
        //System.out.println("decryptedIv: "+bytesToHex(decryptedIv));
        byte[] decryptedDataBytes = Arrays.copyOfRange(encryptedPassword, encryptCipher.getBlockSize(), encryptedPassword.length);
        //System.out.println("decryptedDataBytes: "+bytesToHex(decryptedDataBytes));

        IvParameterSpec decryptedIvSpec = new IvParameterSpec(decryptedIv);
        Cipher decryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        decryptCipher.init(Cipher.DECRYPT_MODE, cipherKey, decryptedIvSpec);
        byte[] decryptedData = decryptCipher.doFinal(decryptedDataBytes);
        String decryptedDataString = new String(decryptedData, StandardCharsets.UTF_8);


/*
        // Create cipher and initialization vector
        Cipher cipher = Cipher.getInstance(cipherAlgorithm);
        byte[] iv = new byte[cipher.getBlockSize()];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        // Decrypt password
        cipher.init(Cipher.DECRYPT_MODE, cipherKey,ivSpec);
        //printCharacterCodePoints(encryptedPassword);
        System.out.println("Decoded back to hex string: "+bytesToHex(encryptedPassword));
        byte[] decryptedPassword = cipher.doFinal(encryptedPassword);

        // Return decrypted password as string

 */
        return decryptedDataString;
    }
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static void printCharacterCodePoints(String input) {
        for (char c : input.toCharArray()) {
            System.out.printf("%c - %d%n", c, (int) c);
        }
    }
}
