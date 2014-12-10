package ee.cyber.sdsb.signer.protocol.handler;

import java.util.List;
import java.util.stream.Collectors;

import ee.cyber.sdsb.common.identifier.ClientId;
import ee.cyber.sdsb.signer.protocol.AbstractRequestHandler;
import ee.cyber.sdsb.signer.protocol.dto.CertificateInfo;
import ee.cyber.sdsb.signer.protocol.dto.KeyUsageInfo;
import ee.cyber.sdsb.signer.protocol.message.GetMemberCerts;
import ee.cyber.sdsb.signer.protocol.message.GetMemberCertsResponse;
import ee.cyber.sdsb.signer.tokenmanager.TokenManager;

/**
 * Handles requests for member certificates.
 */
public class GetMemberCertsRequestHandler
        extends AbstractRequestHandler<GetMemberCerts> {

    @Override
    protected Object handle(GetMemberCerts message) throws Exception {
        List<CertificateInfo> memberCerts = TokenManager.listTokens().stream()
                .flatMap(t -> t.getKeyInfo().stream())
                .filter(k -> k.getUsage() == KeyUsageInfo.SIGNING)
                .flatMap(k -> k.getCerts().stream())
                .filter(c -> containsMember(c.getMemberId(),
                        message.getMemberId()))
                .collect(Collectors.toList());

        return new GetMemberCertsResponse(memberCerts);
    }

    private static boolean containsMember(ClientId first, ClientId second) {
        if (first == null || second == null) {
            return false;
        }

        return first.equals(second) || second.subsystemContainsMember(first);
    }

}
