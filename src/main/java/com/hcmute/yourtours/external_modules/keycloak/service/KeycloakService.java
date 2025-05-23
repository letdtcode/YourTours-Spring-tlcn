package com.hcmute.yourtours.external_modules.keycloak.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcmute.yourtours.constant.RealmConstant;
import com.hcmute.yourtours.exceptions.YourToursErrorCode;
import com.hcmute.yourtours.external_modules.keycloak.config.KeycloakProperties;
import com.hcmute.yourtours.libs.exceptions.ErrorCode;
import com.hcmute.yourtours.libs.exceptions.InvalidException;
import com.hcmute.yourtours.utils.GetInfoToken;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.authorization.client.AuthzClient;
import org.keycloak.authorization.client.Configuration;
import org.keycloak.authorization.client.representation.TokenIntrospectionResponse;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class KeycloakService implements IKeycloakService {
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String GRANT_TYPE = "grant_type";
    private static final String REVOKE_TOKENS_ON_LOGOUT = "revoke_tokens_on_logout";
    private final AuthzClient authzClient;
    private final Keycloak keycloak;
    private final RealmResource realmResource;
    private final String resourceId;
    private final KeycloakProperties securityProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public KeycloakService(Keycloak keycloak,
                           KeycloakProperties securityProperties
    ) {
        this.authzClient = AuthzClient.create(new Configuration(
                securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getAuthServerUrl(),
                securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getRealmName(),
                securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getClientId(),
                securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getCredentials(),
                null));
        this.keycloak = keycloak;
        this.realmResource = keycloak.realm(securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getRealmName());
        this.resourceId = realmResource.clients().findByClientId(
                        securityProperties.getKeycloakRealms().get(
                                RealmConstant.YOURTOUR.getRealmName()
                        ).getClientId()
                ).stream()
                .findFirst()
                .orElseThrow(
                        () -> new NullPointerException("resource id not null")
                ).getId();
        this.securityProperties = securityProperties;
    }

    @Override
    public String createUser(String userName, String firstName, String lastName, String email, String password) throws InvalidException {
        try (
                Response keycloakResponse = keycloak.realm(
                                securityProperties
                                        .getKeycloakRealms()
                                        .get(
                                                RealmConstant.YOURTOUR.getRealmName()).getRealmName()
                        )
                        .users()
                        .create(
                                toUserRepresentation(userName, firstName, lastName, email, toCredentialRepresentation(password))
                        )
        ) {
            if (keycloakResponse.getStatus() == HttpStatus.CONFLICT.value()) {
                throw new InvalidException(YourToursErrorCode.USERNAME_EXIST);
            }
            if (keycloakResponse.getStatus() == HttpStatus.CREATED.value()) {
                return CreatedResponseUtil.getCreatedId(keycloakResponse);
            }
            log.warn("Create user keycloak fail! Response status: {}", keycloakResponse.getStatus());
            throw new InvalidException(YourToursErrorCode.CREATE_USER_FAIL);
        } catch (Exception e) {
            throw new InvalidException(YourToursErrorCode.CREATE_USER_FAIL);
        }
    }

    @Override
    public boolean deleteUser(String userId) {
        try {
            realmResource.users().delete(userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AccessTokenResponse getJwt(String userName, String password) {
        return authzClient.obtainAccessToken(userName, password);
    }

    @Override
    public AccessTokenResponse getRefreshToken(String refreshToken) throws InvalidException {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = requestParams();
        map.add(GRANT_TYPE, REFRESH_TOKEN);
        map.add(REFRESH_TOKEN, refreshToken);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, httpHeaders);

        try {
            ResponseEntity<String> keycloakResponse = restTemplate.postForEntity(
                    securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getTokenUri(),
                    request,
                    String.class
            );
            return new ObjectMapper().readValue(keycloakResponse.getBody(), AccessTokenResponse.class);
        } catch (Exception e) {
            throw new InvalidException(YourToursErrorCode.REFRESH_TOKEN_NOT_VALID);
        }
    }

    protected MultiValueMap<String, String> requestParams() {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(CLIENT_ID, securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getClientId());
        map.add(CLIENT_SECRET, securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getClientSecret());
        return map;
    }

    @Override
    public void addUserClientRoles(String userId, List<String> roles) throws InvalidException {
        List<RoleRepresentation> list = new ArrayList<>();
        for (String role : roles) {
            RoleRepresentation roleRepresentation = findClientRoleByName(role);
            if (roleRepresentation != null) {
                list.add(roleRepresentation);
            }
        }
        realmResource.users().get(userId).roles().clientLevel(resourceId).add(list);
    }

    @Override
    public void addUserRealmRoles(String userId, List<String> roles) throws InvalidException {
        List<RoleRepresentation> list = new ArrayList<>();
        for (String role : roles) {
            RoleRepresentation roleRepresentation = findRealmRoleByName(role);
            if (roleRepresentation != null) {
                list.add(roleRepresentation);
            }
        }
        realmResource.users().get(userId).roles().realmLevel().add(list);
    }

    private RoleRepresentation findClientRoleByName(String role) {
        return realmResource.clients().get(resourceId).roles().get(role).toRepresentation();
    }

    private RoleRepresentation findRealmRoleByName(String role) {
        return realmResource.roles().get(role).toRepresentation();
    }

    @Override
    public void invalidateToken(String refreshToken) throws InvalidException {
        MultiValueMap<String, String> requestParams = requestParams();
        requestParams.add(REFRESH_TOKEN, refreshToken);
        requestParams.add(REVOKE_TOKENS_ON_LOGOUT, "true");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(requestParams, headers);
        try {
            restTemplate.postForEntity(securityProperties.getKeycloakRealms().get(RealmConstant.YOURTOUR.getRealmName()).getTokenInvalidateUri(), request, Object.class);
        } catch (Exception e) {
            e.printStackTrace();
            throw new InvalidException(ErrorCode.BAD_REQUEST);
        }
    }

    @Override
    public void setPassword(String userId, String password) throws InvalidException {
        try {
            realmResource.users().get(userId).resetPassword(toCredentialRepresentation(password));
        } catch (Exception e) {
            throw new InvalidException(YourToursErrorCode.CHANGE_PASSWORD_FAIL);
        }
    }

    @Override
    public boolean introspectToken(String token) {
        try {
            TokenIntrospectionResponse tokenIntrospectionResponse = authzClient.protection()
                    .introspectRequestingPartyToken(token);
            return tokenIntrospectionResponse.getActive() && !tokenIntrospectionResponse.isExpired();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isEmailVerify(String userId) {
        return realmResource.users().get(userId).toRepresentation().isEmailVerified();
    }

    @Override
    public void activeUserEmail(String userId) {
        UserResource userResource = realmResource.users().get(userId);
        UserRepresentation userRepresentation = userResource.toRepresentation();
        userRepresentation.setEmailVerified(true);
        userResource.update(userRepresentation);
    }

    @Override
    public void logout() throws InvalidException {

        String sessionId = GetInfoToken.getCurrentSessionId();
        if (sessionId == null) {
            throw new InvalidException(YourToursErrorCode.LOGOUT_FAIL);
        }
        realmResource.deleteSession(sessionId);

    }

    private UserRepresentation toUserRepresentation(
            String userName,
            String firstName,
            String lastName,
            String email,
            CredentialRepresentation credential
    ) {
        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(userName);
        userRepresentation.setCredentials(List.of(credential));
        userRepresentation.setFirstName(firstName);
        userRepresentation.setLastName(lastName);
        userRepresentation.setEmail(email);
        userRepresentation.setEnabled(true);
        return userRepresentation;
    }

    private CredentialRepresentation toCredentialRepresentation(String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setTemporary(false);
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        return credential;
    }

    @Override
    public Boolean validUserPassword(String username, String password) throws InvalidException {
        try {
            AccessTokenResponse response = getJwt(username, password);
            this.realmResource.deleteSession(response.getSessionState());
            return true;
        } catch (Exception e) {
            throw new InvalidException(YourToursErrorCode.INVALID_CHANGE_PASSWORD);
        }

    }


}
