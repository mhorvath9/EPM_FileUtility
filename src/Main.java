import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;

public class Main {

    static byte[] encryptPassword(String EnvironmentIdentifier, String plainpassword)
    {
        String cipherAlgorithm = "AES/CBC/PKCS5Padding";
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(128); // 128 bits
            SecretKey cipherKey = keyGenerator.generateKey();
            byte[] cipherKeyBytes = cipherKey.getEncoded();


// Create key store
            //KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            KeyStore keyStore = KeyStore.getInstance("JCEKS");
            keyStore.load(null, null); // Initialize empty key store

// Store cipher key in key store
            Key key = new SecretKeySpec(cipherKeyBytes, "AES");
            System.out.println(key);
            KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry((SecretKey) key);
            System.out.println(entry);
            String keyStorePassword="changeme";
            String keyAlias="myKey";
            //String keyStoreFile="\\cfg\\keystore";
            //String keyStoreFile=System.getProperty("user.dir") + File.separator + "cfg" + File.separator + "keystore";
            String jarlocation=getJarLocation(Main.class);
            String keyStoreFile=jarlocation + File.separator + EnvironmentIdentifier+File.separator+"config"+File.separator+EnvironmentIdentifier+".keystore";
            KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(keyStorePassword.toCharArray());
            keyStore.setEntry(keyAlias, entry, protection);

// Save key store to disk
            try (OutputStream out = new FileOutputStream(keyStoreFile)) {
                keyStore.store(out, keyStorePassword.toCharArray());
            }

            Cipher encryptCipher = Cipher.getInstance(cipherAlgorithm);
            byte[] iv = new byte[encryptCipher.getBlockSize()];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            encryptCipher.init(Cipher.ENCRYPT_MODE, cipherKey, ivSpec);
            byte[] encryptedData = encryptCipher.doFinal(plainpassword.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedDataWithIV = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, encryptedDataWithIV, 0, iv.length);
            System.arraycopy(encryptedData, 0, encryptedDataWithIV, iv.length, encryptedData.length);

            System.out.println("encryptedData: "+bytesToHex(encryptedData));
            System.out.println("encryptedDataWithIV: "+bytesToHex(encryptedDataWithIV));
            System.out.println("SecretKey Algorithm: " + cipherKey.getAlgorithm());

            return encryptedDataWithIV;


        } catch (Exception e)
        {
            e.printStackTrace();
        }


return null;
    }

    public static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static String getJarLocation(Class<?> aclass) {
        try {
            return new File(aclass.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args)
    {
        String jarlocation= getJarLocation(Main.class);
        String EnvID=args[0];
        if (args.length == 0)
        {
            System.out.println("DXCAgent.jar usage: ");
            System.out.println("DXCAgent.jar <environment name> [--encrypt]");
            System.out.println("Required: <environment name> - the environment identifier for the desired cloud environment.");
            System.out.println("Optional: [--encrypt] - Encrypts the supplied password in the nominated config ini file.");

            return;
        }

        EnvironmentInformation env = new EnvironmentInformation(EnvID);
        String ConfigLocation= jarlocation + File.separator + EnvID+File.separator+"config"+File.separator+EnvID+".config.ini";
        if(!env.load(ConfigLocation)){
            System.out.println("Supplied Environment Identifier was invalid. Please check and try again. Ensure that "+ConfigLocation+" exists and is correct.");
            return;
        }

        if(args.length > 1)
        if ("--encrypt".equals(args[1])) {
            // Encrypt password and exit
            String password = env.getValue("cloudserver.password");
            byte[] encryptedPassword = encryptPassword(EnvID,password);
            System.out.println("Encrypted password is: "+bytesToHex(encryptedPassword));
            String encryptedPasswordString = Base64.getUrlEncoder().encodeToString(encryptedPassword);
            System.out.println("Encrypted and encoded password is: "+encryptedPasswordString);
            env.setValue("cloudserver.password",encryptedPasswordString);
            env.save(ConfigLocation);
            System.exit(0);
        }

            try {
                DXCAgent agent = new DXCAgent("agent", env);
                Thread agentthread = new Thread(agent);
                System.out.println("main(" + Thread.currentThread().getId() + "): Yield()");
                Thread.yield();

                System.out.println("main(" + Thread.currentThread().getId() + "): Start agent for "+env.EnvironmentIdentifier);
                agentthread.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("main(" + Thread.currentThread().getId() + "): Stop agent for "+env.EnvironmentIdentifier);
                    agent.stopGracefully();
                    try {
                        agentthread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }));

                //for (int i = 0; i < 900; i++) {
                //    //System.out.println("main(" + Thread.currentThread().getId() + "): Sleep for 1000ms...");
                //    Thread.sleep(1000);
                //}
                //System.out.println("main(" + Thread.currentThread().getId() + "): Stop agent");
                //agent.stopGracefully();

                //agentthread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }

    }
}