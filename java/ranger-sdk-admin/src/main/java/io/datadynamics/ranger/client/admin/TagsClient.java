package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.RangerValidationException;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerServiceResource;
import io.datadynamics.ranger.client.model.RangerTag;
import io.datadynamics.ranger.client.model.RangerTagDef;
import io.datadynamics.ranger.client.model.RangerTagResourceMap;

import java.util.List;
import java.util.function.Supplier;

/** Tag store ({@code /service/tags}): tag definitions, tags, service resources and their mapping. */
public final class TagsClient extends Resource {
    TagsClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public List<String> types() {
        return many(HttpCall.get(TAGS + "/types/"), String.class);
    }

    public List<RangerTagDef> listDefs() {
        return many(HttpCall.get(TAGS + "/tagdefs/"), RangerTagDef.class);
    }

    public RangerTagDef getDef(long id) {
        return byId(() -> one(HttpCall.get(TAGS + "/tagdef/" + id), RangerTagDef.class));
    }

    public RangerTagDef getDefByName(String name) {
        return one(HttpCall.get(TAGS + "/tagdef/name/" + seg(name)), RangerTagDef.class);
    }

    public RangerTagDef createDef(RangerTagDef def, boolean updateIfExists) {
        return one(HttpCall.post(TAGS + "/tagdefs/").query("updateIfExists", updateIfExists).json(def), RangerTagDef.class);
    }

    public RangerTagDef updateDef(RangerTagDef def) {
        return one(HttpCall.put(TAGS + "/tagdef/" + def.getId()).json(def), RangerTagDef.class);
    }

    public void deleteDef(long id) {
        byId(() -> exec(HttpCall.delete(TAGS + "/tagdef/" + id)));
    }

    public List<RangerTag> list() {
        return many(HttpCall.get(TAGS + "/tags/"), RangerTag.class);
    }

    public List<RangerTag> byType(String type) {
        return many(HttpCall.get(TAGS + "/tags/type/" + seg(type)), RangerTag.class);
    }

    public RangerTag get(long id) {
        return byId(() -> one(HttpCall.get(TAGS + "/tag/" + id), RangerTag.class));
    }

    public RangerTag create(RangerTag tag, boolean updateIfExists) {
        return one(HttpCall.post(TAGS + "/tags/").query("updateIfExists", updateIfExists).json(tag), RangerTag.class);
    }

    public RangerTag update(RangerTag tag) {
        return one(HttpCall.put(TAGS + "/tag/" + tag.getId()).json(tag), RangerTag.class);
    }

    public void delete(long id) {
        byId(() -> exec(HttpCall.delete(TAGS + "/tag/" + id)));
    }

    /** Service resources, of one service or all when {@code service} is null. */
    public List<RangerServiceResource> listResources(String service) {
        String path = service == null ? TAGS + "/resources/" : TAGS + "/resources/service/" + seg(service);
        return many(HttpCall.get(path), RangerServiceResource.class);
    }

    public RangerServiceResource getResource(long id) {
        return byId(() -> one(HttpCall.get(TAGS + "/resource/" + id), RangerServiceResource.class));
    }

    public RangerServiceResource createResource(RangerServiceResource resource, boolean updateIfExists) {
        return one(HttpCall.post(TAGS + "/resources/").query("updateIfExists", updateIfExists).json(resource),
                RangerServiceResource.class);
    }

    public RangerServiceResource updateResource(RangerServiceResource resource) {
        return one(HttpCall.put(TAGS + "/resource/" + resource.getId()).json(resource), RangerServiceResource.class);
    }

    public void deleteResource(long id) {
        byId(() -> exec(HttpCall.delete(TAGS + "/resource/" + id)));
    }

    public RangerTagResourceMap map(String tagGuid, String resourceGuid) {
        return one(HttpCall.post(TAGS + "/tagresourcemaps/").query("tag-guid", tagGuid).query("resource-guid", resourceGuid),
                RangerTagResourceMap.class);
    }

    public void unmap(String tagGuid, String resourceGuid) {
        exec(HttpCall.delete(TAGS + "/tagresourcemaps/").query("tag-guid", tagGuid).query("resource-guid", resourceGuid));
    }

    /**
     * Ranger's tag REST answers an unknown id with a bare {@code 400 {"statusCode":400}}; real failures (e.g. a
     * tag still mapped to a resource) carry a message. So a 400 without a message on a by-id call means "not found".
     */
    private static <T> T byId(Supplier<T> call) {
        try {
            return call.get();
        } catch (RangerValidationException e) {
            if (e.getServerMessage() == null) {
                throw notFound(e);
            }
            throw e;
        }
    }

    private static void byId(Runnable call) {
        byId(() -> {
            call.run();
            return null;
        });
    }
}
