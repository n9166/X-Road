java_import Java::ee.cyber.sdsb.common.conf.serverconf.model.ClientType
java_import Java::ee.cyber.sdsb.common.conf.serverconf.dao.ClientDAOImpl
java_import Java::ee.cyber.sdsb.common.conf.serverconf.ServerConfDatabaseCtx
java_import Java::ee.cyber.sdsb.common.identifier.SdsbObjectType
java_import Java::ee.cyber.sdsb.commonui.SignerProxy

class ClientsController < ApplicationController

  include Clients::Groups
  include Clients::InternalCerts
  include Clients::Services
  include Clients::AclSubjects

  def index
    authorize!(:view_clients)

    @instances = GlobalConf::getInstanceIdentifiers

    @member_classes = GlobalConf::getMemberClasses
    @member_classes_instance = GlobalConf::getMemberClasses(sdsb_instance)
    
    @subject_types = [
      SdsbObjectType::MEMBER.toString(),
      SdsbObjectType::SUBSYSTEM.toString(),
      SdsbObjectType::GLOBALGROUP.toString(),
      SdsbObjectType::LOCALGROUP.toString(),
    ]

    @member_types = [
      SdsbObjectType::MEMBER.toString(),
      SdsbObjectType::SUBSYSTEM.toString()
    ]
  end

  def clients_refresh
    authorize!(:view_clients)

    render_json(read_clients)
  end

  def clients_search
    authorize!(:view_clients)

    validate_params({
      :search_member => []
    })

    members = []
    GlobalConf::getMembers(sdsb_instance).each do |member|
      if match(member.id.memberClass, params[:search_member]) ||
          match(member.id.memberCode, params[:search_member]) ||
          match(member.id.subsystemCode, params[:search_member]) ||
          match(member.name, params[:search_member])

        members << {
          :member_name => member.name,
          :member_class => member.id.memberClass,
          :member_code => member.id.memberCode,
          :subsystem_code => member.id.subsystemCode
        }
      end
    end

    render_json(members)
  end

  def client_name
    authorize!(:add_client)

    validate_params({
      :add_member_class => [],
      :add_member_code => []
    })

    name = get_member_name(params[:add_member_class], params[:add_member_code])

    render_json(:name => name)
  end

  def client_add
    authorize!(:add_client)

    validate_params({
      :add_member_class => [:required],
      :add_member_code => [:required],
      :add_subsystem_code => []
    })

    if params[:add_subsystem_code].empty?
      params[:add_subsystem_code] = nil
    end

    client_id = ClientId.create(
      sdsb_instance,
      params[:add_member_class],
      params[:add_member_code],
      params[:add_subsystem_code])

    # check if client exists in serverconf
    serverconf.client.each do |client|
      if client.identifier.equals(client_id)
        raise t('clients.client_exists')
      end
    end

    member_name = get_member_name(
      params[:add_member_class], params[:add_member_code])

    member_string = "#{member_name} #{params[:add_member_class]}: " \
      "#{params[:add_member_code]}"

    if !member_name
      warn("new_member", t('clients.unregistered_member', :member => member_string))
    end

    if params[:add_subsystem_code]
      member_found = GlobalConf::getMembers(sdsb_instance).index do |member|
        member.id == client_id
      end

      unless member_found
        warn("new_subsys", t('clients.new_subsystem',
          :subsystem => params[:add_subsystem_code], :member => member_string))
      end
    end

    client = ClientType.new
    client.identifier = get_identifier(client_id)
    client.clientStatus = ClientType::STATUS_SAVED
    client.isAuthentication = "NOSSL"
    client.conf = serverconf

    serverconf.client.add(client)
    serverconf_save

    after_commit do
      export_services
    end

    render_json(read_clients)
  end

  def client_certs
    authorize!(:view_client_details)

    validate_params({
      :client_id => [:required]
    })

    client_id = get_client(params[:client_id]).identifier

    tokens = SignerProxy::getTokens

    certs = []
    tokens.each do |token|
      token.keyInfo.each do |key|
        key.certs.each do |cert|
          next unless client_id.memberEquals(cert.memberId)

          cert_bytes = String.from_java_bytes(cert.certificateBytes)
          cert_obj = OpenSSL::X509::Certificate.new(cert_bytes)

          certs << {
            :csp => CommonUi::CertUtils.cert_csp(cert_obj),
            :serial => cert_obj.serial.to_s,
            :state => cert.active ?
              t('clients.cert_in_use') : t('clients.cert_disabled'),
            :expires => cert_obj.not_after.strftime("%F")
          }
        end if key.certs
      end if token.keyInfo
    end

    render_json(certs)
  end

  def client_regreq
    authorize!(:send_client_reg_req)

    validate_params({
      :member_class => [:required],
      :member_code => [:required],
      :subsystem_code => []
    })

    if params[:subsystem_code] && params[:subsystem_code].empty?
      params[:subsystem_code] = nil
    end

    client_id = ClientId.create(
      sdsb_instance,
      params[:member_class],
      params[:member_code],
      params[:subsystem_code])

    if client_id == owner_identifier
      raise t('clients.cannot_register_owner')
    end

    if x55_installed?
      sign_cert_exists = false

      catch :cert_checked do
        SignerProxy::getTokens.each do |token|
          token.keyInfo.each do |key|
            next unless key.usage == KeyUsageInfo::SIGNING

            key.certs.each do |cert|
              if client_id.memberEquals(cert.memberId)
                sign_cert_exists = true
                throw :cert_checked
              end
            end
          end
        end
      end

      unless sign_cert_exists
        raise t('clients.cannot_register_without_sign_cert')
      end
    end

    register_client(client_id)

    client = get_client(client_id.toString)
    client.clientStatus = ClientType::STATUS_REGINPROG

    serverconf_save

    render_json(client_to_json(client))
  end

  def client_delreq
    authorize!(:send_client_del_req)

    validate_params({
      :member_class => [:required],
      :member_code => [:required],
      :subsystem_code => []
    })

    if params[:subsystem_code] && params[:subsystem_code].empty?
      params[:subsystem_code] = nil
    end

    client_id = ClientId.create(
      sdsb_instance,
      params[:member_class],
      params[:member_code],
      params[:subsystem_code])

    if client_id == owner_identifier
      raise t('clients.cannot_delete_owner')
    end

    unregister_client(client_id)

    client = get_client(client_id.toString)
    client.clientStatus = ClientType::STATUS_DELINPROG

    serverconf_save

    render_json(client_to_json(client))
  end

  def client_delete
    authorize!(:delete_client)

    validate_params({
      :client_id => [:required]
    })

    client = get_client(params[:client_id])

    if client.identifier == owner_identifier
      raise t('clients.cannot_delete_owner')
    end

    serverconf.client.remove(client)
    serverconf_save

    # keep this client's id in cache in case its certs are also
    # deleted
    clients = read_clients
    cache_client_id(client)

    deleted_id = client.identifier
    ask_delete_certs = true

    serverconf.client.each do |client|
      client_id = client.identifier

      if client_id.memberClass == deleted_id.memberClass &&
          client_id.memberCode == deleted_id.memberCode
        # other clients using same cert
        ask_delete_certs = false
      end
    end

    catch(:done) do
      SignerProxy::getTokens.each do |token|
        token.keyInfo.each do |key|
          key.certs.each do |cert|
            if cert.memberId &&
                cert.memberId.memberClass == deleted_id.memberClass &&
                cert.memberId.memberCode == deleted_id.memberCode
              throw :done
            end
          end

          key.certRequests.each do |cert_request|
            if cert_request.memberId &&
                cert_request.memberId.memberClass == deleted_id.memberClass &&
                cert_request.memberId.memberCode == deleted_id.memberCode
              throw :done
            end
          end
        end
      end

      # no certs or requests found for deleted client
      ask_delete_certs = false
    end if ask_delete_certs

    after_commit do
      export_services(client.identifier)
    end

    render_json({
      :clients => clients,
      :ask_delete_certs => ask_delete_certs
    })
  end

  def client_delete_certs
    authorize!(:delete_client)

    validate_params({
      :client_id => [:required]
    })

    client_id = get_cached_client_id(params[:client_id])

    SignerProxy::getTokens.each do |token|
      token.keyInfo.each do |key|
        key.certs.each do |cert|
          if client_id.memberEquals(cert.memberId)
            SignerProxy::deleteCert(cert.id)
          end
        end

        key.certRequests.each do |cert_request|
          if client_id.memberEquals(cert_request.memberId)
            SignerProxy::deleteCertRequest(cert_request.id)
          end
        end
      end
    end

    render_json
  end

  private

  def read_clients
    clients = []

    serverconf.client.each do |client|
      clients << client_to_json(client)
    end

    cache_client_ids

    clients
  end

  def client_to_json(client)
    {
      :client_id => client.identifier.toString,
      :member_name => GlobalConf::getMemberName(client.identifier),
      :type => client.identifier.objectType.toString,
      :instance => client.identifier.sdsbInstance,
      :member_class => client.identifier.memberClass,
      :member_code => client.identifier.memberCode,
      :subsystem_code => client.identifier.subsystemCode,
      :state => client.clientStatus,
      :contact => client.contacts,
      :register_enabled =>
        [ClientType::STATUS_SAVED].include?(client.clientStatus),
      :unregister_enabled =>
        [ClientType::STATUS_REGINPROG,
         ClientType::STATUS_REGISTERED].include?(client.clientStatus),
      :delete_enabled =>
        [ClientType::STATUS_SAVED,
         ClientType::STATUS_DELINPROG,
         ClientType::STATUS_GLOBALERR].include?(client.clientStatus),
      :owner => serverconf.owner.id == client.getId,
      :can_view_client_details => can?(:view_client_details),
      :can_view_client_services => can?(:view_client_services),
      :can_view_client_local_groups => can?(:view_client_local_groups),
      :can_view_client_acl_subjects => can?(:view_client_acl_subjects),
      :can_view_client_internal_certs => can?(:view_client_internal_certs)
    }
  end

  def cache_client_ids
    session[:client_ids] = {}

    serverconf.client.each do |client|
      session[:client_ids][client.identifier.toString] = client.identifier
    end

    session[:client_ids]
  end

  def cache_client_id(client)
    session[:client_ids][client.identifier.toString] = client.identifier
  end

  def get_cached_client_id(key)
    get_identifier(session[:client_ids][key])
  end

  def get_client(key)
    ClientDAOImpl.instance.getClient(
      ServerConfDatabaseCtx.session, session[:client_ids][key])
  end
end
