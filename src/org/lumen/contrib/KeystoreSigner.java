// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** The unit's contribution key in the Android Keystore (EC P-256, signing only, never exported). Made on first use;
 *  clearing the app's data or reinstalling it makes a new one, the base accepts a few per projector. */
public final class KeystoreSigner implements Signer {
    static final String ALIAS = "lumen-contrib";

    private static KeyStore store() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return ks;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static PrivateKey key() throws GeneralSecurityException {
        KeyStore ks = store();
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            g.generateKeyPair();
        }
        return (PrivateKey) ks.getKey(ALIAS, null);
    }

    @Override
    public byte[] publicKey() throws GeneralSecurityException {
        key();
        return store().getCertificate(ALIAS).getPublicKey().getEncoded();
    }

    @Override
    public byte[] sign(byte[] body) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key());
        s.update(body);
        return s.sign();
    }
}
