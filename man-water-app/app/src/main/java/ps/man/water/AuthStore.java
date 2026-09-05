package ps.man.water;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AuthStore {
    private static final String ALIAS="man_water_account_v1", USER="auth_user", SECRET="auth_secret";
    private AuthStore(){}

    public static void save(Context context,String username,String password)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);
        context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE).edit().putString(USER,username).putString(SECRET,value).putBoolean("reauth_required",false).apply();
    }

    public static boolean ready(Context context){return !context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE).getString(SECRET,"").isEmpty();}
    public static String username(Context context){return context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE).getString(USER,"");}
    public static String password(Context context)throws Exception{
        String value=context.getSharedPreferences(MainActivity.ACCOUNT_PREFS,Context.MODE_PRIVATE).getString(SECRET,"");String[] parts=value.split(":",2);if(parts.length!=2)throw new IllegalStateException("missing credentials");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }

    private static SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
}
