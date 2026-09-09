package com.geupddong.account;

import software.amazon.awssdk.services.s3.S3Client;

/** Compatibility facade: the authenticated ledger format is shared with local storage. */
public final class R2ErasureLedger extends ObjectErasureLedger {
    public R2ErasureLedger(S3Client s3, ErasureCipher cipher, String bucket, String realm) {
        this(s3, cipher, bucket, realm, false);
    }
    public R2ErasureLedger(S3Client s3, ErasureCipher cipher, String bucket, String realm, boolean catalogueEnabled) {
        super(new S3ErasureObjectStore(s3, bucket), cipher, realm, catalogueEnabled);
    }
}
