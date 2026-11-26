// SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
package org.lumen.contrib;

import java.security.GeneralSecurityException;

/** Holds the unit's contribution key (P-256). The private key never leaves it. */
public interface Signer {
    /** SubjectPublicKeyInfo DER of the public key. */
    byte[] publicKey() throws GeneralSecurityException;

    /** SHA256withECDSA over the body, DER encoded. */
    byte[] sign(byte[] body) throws GeneralSecurityException;
}
