package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.auth.kerberos.KerberosAuth;
import io.datadynamics.ranger.client.core.auth.AuthProvider;

/** Kept apart so the optional Kerberos module is only loaded when {@code auth.type=kerberos}. */
final class KerberosSupport {
    private KerberosSupport() {
    }

    static AuthProvider create(String prefix, RangerProperties.Auth a) {
        ClientSettings.require(a.getPrincipal(), prefix + ".principal");
        KerberosAuth k;
        if (a.getKeytab() != null) {
            k = KerberosAuth.keytab(a.getPrincipal(), a.getKeytab());
        } else {
            k = KerberosAuth.ticketCache(a.getPrincipal(), a.getTicketCache());
        }
        if (a.getKrb5Conf() != null) {
            k = k.krb5Conf(a.getKrb5Conf());
        }
        if (a.getSpnegoService() != null) {
            k = k.service(a.getSpnegoService());
        }
        if (a.getSpnegoHostname() != null) {
            k = k.hostname(a.getSpnegoHostname());
        }
        return k;
    }
}
