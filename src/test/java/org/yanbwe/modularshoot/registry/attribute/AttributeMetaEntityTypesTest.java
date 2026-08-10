package org.yanbwe.modularshoot.registry.attribute;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.datapack.AttributeMetaJsonCodec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code entity_types} read-whitelist of {@link AttributeMeta}:
 * the field is optional (defaults to player-only), parses explicit entity
 * type lists with order preserved, rejects unknown type names, and drives
 * {@link AttributeMeta#allowsEntity}.
 *
 * <p>The codec resolves entity type names through
 * {@code BuiltInRegistries.ENTITY_TYPE}, whose static initializer requires a
 * bootstrapped game. Following the probe-verified pattern of
 * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest}, a static
 * block bootstraps the vanilla registries once per JVM; this test only
 * <em>reads</em> entity types, so no {@code GameData.unfreezeData()} is
 * needed.</p>
 */
class AttributeMetaEntityTypesTest {

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to ModularShootAPIItemBindingTest's probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static DataResult<AttributeMeta> parse(String json) {
        return AttributeMetaJsonCodec.parseSafe(JsonParser.parseString(json));
    }

    private static AttributeMeta success(DataResult<AttributeMeta> result) {
        assertTrue(result.isSuccess(), () -> "parse should succeed, got: " + result.error());
        return result.getOrThrow(msg -> new AssertionError("unexpected parse error: " + msg));
    }

    @Test
    void codecDefaultIsPlayerOnly() {
        AttributeMeta meta = success(parse("{\"binds\":\"m:x\",\"default_value\":1.0}"));
        assertEquals(List.of(EntityType.PLAYER), meta.entityTypes(),
                "缺少 entity_types 字段时缺省为仅玩家，保持现状行为");
    }

    @Test
    void codecParsesExplicitList() {
        AttributeMeta meta = success(parse(
                "{\"binds\":\"m:x\",\"default_value\":1.0,"
                        + "\"entity_types\":[\"minecraft:player\",\"minecraft:skeleton\"]}"));
        assertEquals(List.of(EntityType.PLAYER, EntityType.SKELETON), meta.entityTypes(),
                "显式声明的实体类型列表按声明顺序保留");
    }

    @Test
    void codecRejectsUnknownType() {
        DataResult<AttributeMeta> result = parse(
                "{\"binds\":\"m:x\",\"default_value\":1.0,"
                        + "\"entity_types\":[\"minecraft:not_a_thing\"]}");
        assertTrue(result.isError(),
                "未知实体类型必须让解析失败而不是抛出异常");
    }

    @Test
    void allowsEntityWhiteListHitAndMiss() {
        AttributeMeta meta = success(parse(
                "{\"binds\":\"m:x\",\"default_value\":1.0,"
                        + "\"entity_types\":[\"minecraft:player\",\"minecraft:skeleton\"]}"));
        assertTrue(meta.allowsEntity(EntityType.SKELETON),
                "白名单内的实体类型应被允许");
        assertFalse(meta.allowsEntity(EntityType.ZOMBIE),
                "白名单外的实体类型不应被允许");
    }

    @Test
    void allowsEntityDefaultPlayer() {
        AttributeMeta meta = AttributeMeta.of(ResourceLocation.parse("m:x"), 1.0);
        assertTrue(meta.allowsEntity(EntityType.PLAYER),
                "缺省白名单应只允许玩家");
        assertFalse(meta.allowsEntity(EntityType.SKELETON),
                "缺省白名单不应允许其他实体");
    }

    @Test
    void ofFactoryStillWorks() {
        AttributeMeta meta = AttributeMeta.of(ResourceLocation.parse("m:x"), 1.0);
        assertEquals(ResourceLocation.parse("m:x"), meta.binds());
        assertEquals(1.0, meta.defaultValue());
        assertEquals(List.of(EntityType.PLAYER), meta.entityTypes(),
                "of() 便捷构造的 entityTypes 缺省为仅玩家");
        assertEquals("", meta.description());
        assertEquals(Optional.empty(), meta.color());
        assertEquals(0, meta.priority());
        assertFalse(meta.forceShow());
        assertEquals(Optional.empty(), meta.unit());
    }
}
