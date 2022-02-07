package sh.libre.scim.core;

import com.unboundid.scim2.client.ScimService;
import com.unboundid.scim2.common.exceptions.ScimException;
import com.unboundid.scim2.common.types.Email;
import com.unboundid.scim2.common.types.Meta;
import com.unboundid.scim2.common.types.Name;
import com.unboundid.scim2.common.types.UserResource;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.lang.RuntimeException;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.TypedQuery;
import javax.ws.rs.client.Client;

import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import sh.libre.scim.jpa.ScimResource;

public class ScimClient {

    final private Logger LOGGER = Logger.getLogger(ScimClient.class);
    final private Client client = ResteasyClientBuilder.newClient();
    final private ScimService scimService;
    final private RetryRegistry registry;
    final private String name;
    final private KeycloakSession session;

    public ScimClient(String name, String url, KeycloakSession session) {
        this.name = name;

        this.session = session;
        var target = client.target(url);
        scimService = new ScimService(target);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(10)
                .intervalFunction(IntervalFunction.ofExponentialBackoff())
                .build();
        registry = RetryRegistry.of(retryConfig);
    }

    private EntityManager getEM() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    private String getRealmId() {
        return session.getContext().getRealm().getId();
    }

    public void createUser(UserModel kcUser) {
        LOGGER.info("Create User");
        var user = toUser(kcUser);
        var retry = registry.retry("create-" + kcUser.getId());
        var spUser = retry.executeSupplier(() -> {
            try {
                return scimService.create("Users", user);
            } catch (ScimException e) {
                throw new RuntimeException(e);
            }
        });
        var scimUser = toScimUser(spUser);
        getEM().persist(scimUser);
    }

    public void replaceUser(UserModel kcUser) {
        LOGGER.info("Replace User");
        try {
            var resource = querUserById(kcUser.getId());
            var user = toUser(kcUser);
            user.setId(resource.getRemoteId());
            var meta = new Meta();
            var uri = new URI("Users/" + user.getId());
            meta.setLocation(uri);
            user.setMeta(meta);
            var retry = registry.retry("replace-" + kcUser.getId());
            retry.executeSupplier(() -> {
                try {
                    return scimService.replace(user);
                } catch (ScimException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (NoResultException e) {
            LOGGER.warnf("Failde to repalce user %s, scim mapping not found", kcUser.getId());
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    public void deleteUser(String userId) {
        LOGGER.info("Delete User");
        try {
            var resource = querUserById(userId);
            var retry = registry.retry("delete-" + userId);
            retry.executeSupplier(() -> {
                try {
                    scimService.delete("Users", resource.getRemoteId());
                } catch (ScimException e) {
                    throw new RuntimeException(e);
                }
                return "";
            });
            getEM().remove(resource);
        } catch (NoResultException e) {
            LOGGER.warnf("Failde to delete user %s, scim mapping not found", userId);
        }
    }

    private TypedQuery<ScimResource> queryUser(String query) {
        return getEM()
                .createNamedQuery(query, ScimResource.class)
                .setParameter("realmId", getRealmId())
                .setParameter("type", "Users")
                .setParameter("serviceProvider", name);
    }

    private ScimResource querUserById(String id) {
        return queryUser("findByLocalId").setParameter("id", id).getSingleResult();
    }

    private ScimResource scimUser() {
        var resource = new ScimResource();
        resource.setType("Users");
        resource.setRealmId(getRealmId());
        resource.setServiceProvider(name);
        return resource;
    }

    private ScimResource toScimUser(UserResource user) {
        var resource = scimUser();
        resource.setRemoteId(user.getId());
        resource.setLocalId(user.getExternalId());
        return resource;
    }

    private UserResource toUser(UserModel kcUser) {
        var user = new UserResource();
        user.setExternalId(kcUser.getId());
        user.setUserName(kcUser.getUsername());
        var name = new Name();
        name.setGivenName(kcUser.getFirstName());
        name.setFamilyName(kcUser.getLastName());
        user.setName(name);

        var emails = new ArrayList<Email>();
        if (kcUser.getEmail() != "") {
            var email = new Email().setPrimary(true).setValue(kcUser.getEmail());
            emails.add(email);
        }
        user.setEmails(emails);
        return user;
    }

    public void close() {
        client.close();
    }
}