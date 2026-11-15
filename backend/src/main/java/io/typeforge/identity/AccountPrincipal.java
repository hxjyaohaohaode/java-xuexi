package io.typeforge.identity;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The authentication version is fixed before password verification and checked again at admission. */
public final class AccountPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final UUID userId;
    private final long authEpoch;
    public AccountPrincipal(UUID userId,String username,String hash,boolean active,long authEpoch,Collection<? extends GrantedAuthority> authorities) {
        super(username,hash,active,true,true,true,authorities);
        this.userId=userId;this.authEpoch=authEpoch;
    }
    public UUID userId() { return userId; }
    public long authEpoch() { return authEpoch; }
}
