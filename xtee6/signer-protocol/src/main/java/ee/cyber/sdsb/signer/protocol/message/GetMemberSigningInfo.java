package ee.cyber.sdsb.signer.protocol.message;

import java.io.Serializable;

import lombok.Value;

import ee.cyber.sdsb.common.identifier.ClientId;

/**
 * Signer API message.
 */
@Value
public class GetMemberSigningInfo implements Serializable {

    private final ClientId memberId;
}
