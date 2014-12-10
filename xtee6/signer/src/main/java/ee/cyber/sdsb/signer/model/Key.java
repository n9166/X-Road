package ee.cyber.sdsb.signer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Data;

import ee.cyber.sdsb.signer.protocol.dto.CertRequestInfo;
import ee.cyber.sdsb.signer.protocol.dto.CertificateInfo;
import ee.cyber.sdsb.signer.protocol.dto.KeyInfo;
import ee.cyber.sdsb.signer.protocol.dto.KeyUsageInfo;

/**
 * Model object representing a key.
 */
@Data
public final class Key {

    /** Reference to the token this key belongs to. */
    private final Token token;

    /** The unique key id. */
    private final String id;

    /** Whether of not this key is available. */
    private boolean available;

    /** Key usage info. */
    private KeyUsageInfo usage;

    /** The friendly name of the key. */
    private String friendlyName;

    /** The X509 encoded public key. */
    private String publicKey;

    /** List of certificates. */
    private final List<Cert> certs = new ArrayList<>();

    /** List of certificate requests. */
    private final List<CertRequest> certRequests = new ArrayList<>();

    /**
     * Adds a certificate to this key.
     * @param cert the certificate to add
     */
    public void addCert(Cert cert) {
        certs.add(cert);
    }

    /**
     * Adds a certificate request to this key.
     * @param certReq the certificate request to add
     */
    public void addCertRequest(CertRequest certReq) {
        certRequests.add(certReq);
    }

    /**
     * Converts this object to value object.
     * @return the value object
     */
    public KeyInfo toDTO() {
        return new KeyInfo(available, usage, friendlyName, id, publicKey,
                Collections.unmodifiableList(getCertsAsDTOs()),
                Collections.unmodifiableList(getCertRequestsAsDTOs()));
    }

    private List<CertificateInfo> getCertsAsDTOs() {
        return certs.stream().map(c -> c.toDTO()).collect(Collectors.toList());
    }

    private List<CertRequestInfo> getCertRequestsAsDTOs() {
        return certRequests.stream().map(c -> c.toDTO())
                .collect(Collectors.toList());
    }
}
