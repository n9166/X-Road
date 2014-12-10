java_import Java::java.util.ArrayList

java_import Java::org.bouncycastle.pkcs.PKCS10CertificationRequest
java_import Java::org.bouncycastle.asn1.x500.style.BCStyle

java_import Java::ee.cyber.sdsb.common.identifier.SdsbObjectType
java_import Java::ee.cyber.sdsb.common.util.CertUtils
java_import Java::ee.cyber.sdsb.common.util.CryptoUtils
java_import Java::ee.cyber.sdsb.commonui.SignerProxy
java_import Java::ee.cyber.sdsb.proxyui.ImportCertUtil
java_import Java::ee.cyber.sdsb.signer.protocol.dto.CertificateInfo
java_import Java::ee.cyber.sdsb.signer.protocol.dto.KeyUsageInfo
java_import Java::ee.cyber.sdsb.signer.protocol.dto.TokenInfo
java_import Java::ee.cyber.sdsb.signer.protocol.dto.TokenStatusInfo

class KeysController < ApplicationController

  def index
    authorize!(:view_keys)

    @client_ids = cache_client_ids
  end

  def refresh
    authorize!(:view_keys)

    cache_client_ids
    render_tokens
  end

  def generate_key
    authorize!(:generate_key)

    key = SignerProxy::generateKey(params[:token_id])

    token = get_token(params[:token_id])

    # lets make a clone with just the generated key inside
    clone = TokenInfo.new(
      token.type,
      token.friendlyName,
      token.id,
      token.readOnly,
      token.available,
      token.active,
      token.serialNumber,
      token.label,
      token.slotIndex,
      token.status,
      ArrayList.new,
      token.tokenInfo)

    clone.keyInfo.add(key)

    @tokens = [clone]
    render :partial => "refresh"
  end

  def generate_csr
    if params[:key_usage] == "auth"
      authorize!(:generate_auth_cert_req)
    else
      authorize!(:generate_sign_cert_req)
    end

    client_id = get_cached_client_id(params[:member_id])

    key_usage = params[:key_usage] == "auth" ?
      KeyUsageInfo::AUTHENTICATION : KeyUsageInfo::SIGNING

    csr = SignerProxy::generateCertRequest(
      params[:key_id], client_id, key_usage, params[:subject_name])

    csr_file = SecureRandom.hex(4)

    File.open(CommonUi::IOUtils.temp_file(csr_file), 'wb') do |f|
      f.write(csr)
    end

    render_json({
      :tokens => view_context.columns(SignerProxy::getTokens),
      :redirect => csr_file
    })
  end

  def download_csr
    validate_params({
      :csr => [:required, :filename],
      :key_usage => [:required]
    })

    file = CommonUi::IOUtils.temp_file(params[:csr])

    # file name parts
    subject = get_csr_subject(file)
    date = Time.now.strftime("%Y%m%d")

    send_file(file, :filename =>
      "#{params[:key_usage]}_cert_request_#{date}#{subject}.p10")
  end

  def import_cert
    validate_params({
      :file => [:required]
    })

    uploaded_cert = CommonUi::CertUtils.pem_to_der(params[:file].read)

    java_cert_obj = CryptoUtils::readCertificate(uploaded_cert.to_java_bytes)

    client_id = nil
    cert_state = nil

    if CertUtils::isAuthCert(java_cert_obj)
      authorize!(:import_auth_cert)

      cert_state = CertificateInfo::STATUS_SAVED
    else
      authorize!(:import_sign_cert)

      client_id = ImportCertUtil::getClientIdForSigningCert(
        sdsb_instance, java_cert_obj)

      ImportCertUtil::verifyClientExists(client_id)
      cert_state = CertificateInfo::STATUS_REGISTERED
    end

    SignerProxy::importCert(uploaded_cert.to_java_bytes, cert_state, client_id)

    notice(t('keys.cert_loaded'))

    upload_success
  end

  def import
    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    cert = get_cert(params[:token_id], params[:key_id], params[:cert_id])
    java_cert_obj = CryptoUtils::readCertificate(cert.certificateBytes)

    client_id = nil
    cert_state = nil

    if CertUtils::isAuthCert(java_cert_obj)
      authorize!(:import_auth_cert)
      cert_state = CertificateInfo::STATUS_SAVED
    else
      authorize!(:import_sign_cert)

      client_id = ImportCertUtil::getClientIdForSigningCert(
        sdsb_instance, java_cert_obj)

      ImportCertUtil::verifyClientExists(client_id)
      cert_state = CertificateInfo::STATUS_REGISTERED
    end

    SignerProxy::importCert(cert.certificateBytes, cert_state, client_id)

    render_tokens
  end

  def activate_cert
    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    key = get_key(params[:token_id], params[:key_id])

    if key.usage == KeyUsageInfo::AUTHENTICATION
      authorize!(:activate_disable_auth_cert)
    else
      authorize!(:activate_disable_sign_cert)
    end

    SignerProxy::activateCert(params[:cert_id])

    render_tokens
  end

  def deactivate_cert
    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    key = get_key(params[:token_id], params[:key_id])

    if key.usage == KeyUsageInfo::AUTHENTICATION
      authorize!(:activate_disable_auth_cert)
    else
      authorize!(:activate_disable_sign_cert)
    end

    SignerProxy::deactivateCert(params[:cert_id])

    render_tokens
  end

  def register
    authorize!(:send_auth_cert_reg_req)

    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required],
      :address => [:required]
    })

    cert = get_cert(params[:token_id], params[:key_id], params[:cert_id])
    register_cert(params[:address], cert.certificateBytes)

    notice(t('keys.request_sent'))

    SignerProxy::setCertStatus(cert.id, CertificateInfo::STATUS_REGINPROG)

    render_tokens
  end

  def unregister
    authorize!(:send_auth_cert_del_req)

    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    cert = get_cert(params[:token_id], params[:key_id], params[:cert_id])
    begin
      unregister_cert(cert.certificateBytes)
      notice(t('keys.request_sent'))
    rescue
      warn("delreq_failed", t('keys.delreq_failed', :msg => $!.message))
    end

    SignerProxy::setCertStatus(cert.id, CertificateInfo::STATUS_DELINPROG)

    render_tokens
  end

  def delete_key
    validate_params({
      :token_id => [:required],
      :key_id => [:required]
    })

    key = get_key(params[:token_id], params[:key_id])

    if key.usage == KeyUsageInfo::AUTHENTICATION
      authorize!(:delete_auth_key)

      key.certs.each do |cert|
        if [CertificateInfo::STATUS_REGINPROG,
            CertificateInfo::STATUS_REGISTERED].include?(cert.status)
          authorize!(:send_auth_cert_del_req)
          unregister_cert(cert.certificateBytes)
          SignerProxy::setCertStatus(cert.id, CertificateInfo::STATUS_DELINPROG)
        end
      end
    elsif key.usage == KeyUsageInfo::SIGNING
      authorize!(:delete_sign_key)
    else
      authorize!(:delete_key)
    end

    SignerProxy::deleteKey(params[:key_id])

    render_tokens
  end

  def delete_cert_request
    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    key = get_key(params[:token_id], params[:key_id])

    if key.usage == KeyUsageInfo::AUTHENTICATION
      authorize!(:delete_auth_cert)
    else
      authorize!(:delete_sign_cert)
    end

    SignerProxy::deleteCertRequest(params[:cert_id])

    render_tokens
  end

  def delete_cert
    validate_params({
      :token_id => [:required],
      :key_id => [:required],
      :cert_id => [:required]
    })

    key = get_key(params[:token_id], params[:key_id])

    if key.usage == KeyUsageInfo::AUTHENTICATION
      authorize!(:delete_auth_cert)
    else
      authorize!(:delete_sign_cert)
    end

    SignerProxy::deleteCert(params[:cert_id])

    render_tokens
  end

  def friendly_name
    validate_params({
      :friendly_name => [:required],
      :token_id => [],
      :key_id => []
    })

    if params[:token_id]
      SignerProxy::setTokenFriendlyName(
        params[:token_id], params[:friendly_name])
    elsif params[:key_id]
      SignerProxy::setKeyFriendlyName(
        params[:key_id], params[:friendly_name])
    end

    render_tokens
  end

  def token_details
    @token = get_token(params[:token_id])

    render :partial => "token_details"
  end

  def key_details
    @key = get_key(params[:token_id], params[:key_id])

    render :partial => "key_details"
  end

  def cert_details
    cert = get_cert(params[:token_id], params[:key_id], params[:cert_id])

    render_json({
      :dump => CommonUi::CertUtils.cert_dump(cert.certificateBytes),
      :hash => CommonUi::CertUtils.cert_hash(cert.certificateBytes)
    })
  end

  private

  def render_tokens
    @tokens = SignerProxy::getTokens

    render :partial => "refresh"
  end

  def get_token(token_id)
    SignerProxy::getTokens.each do |token|
      return token if token.id == token_id
    end

    raise "token not found"
  end

  def get_key(token_id, key_id)
    @token = get_token(token_id)
    @token.keyInfo.each do |key|
      return key if key.id == key_id
    end

    raise "key not found"
  end

  def get_cert(token_id, key_id, cert_id)
    get_key(token_id, key_id).certs.each do |cert|
      return cert if cert.id == cert_id
    end

    raise "cert not found"
  end

  def cache_client_ids
    session[:client_ids] = {}

    serverconf.client.each do |client|
      # no certs for subsystems
      client_id = ClientId.create(
        client.identifier.sdsbInstance,
        client.identifier.memberClass,
        client.identifier.memberCode, nil)

      session[:client_ids][client_id.toString] = client_id
    end

    session[:client_ids]
  end

  def get_cached_client_id(key)
    get_identifier(session[:client_ids][key])
  end

  # returns subject in format "_C_xx_O_xx_CN_xx"
  def get_csr_subject(file)
    bytes = IO.read(file).to_java_bytes
    csr = PKCS10CertificationRequest.new(bytes)

    result = ""
    csr.subject.getRDNs.each do |rdn|
      if BCStyle::C == rdn.first.type
        result += "_C_#{rdn.first.value}"
      elsif BCStyle::O == rdn.first.type
        result += "_O_#{rdn.first.value}"
      elsif BCStyle::CN == rdn.first.type
        result += "_CN_#{rdn.first.value}"
      end
    end

    result
  end
end
