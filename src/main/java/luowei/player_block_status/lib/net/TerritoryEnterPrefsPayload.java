package luowei.player_block_status.lib.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.org.EntityDisplayNames;

/**
 * 进出自己的领地提示偏好：自己的提示文案 + infotype（off / sight of me / sight of others）。
 * C2S 进服同步客户端配置；S2C 把指令结果写回客户端配置文件。
 */
public record TerritoryEnterPrefsPayload(String ownEnterMessage, String infoType) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TerritoryEnterPrefsPayload> TYPE =
			new CustomPacketPayload.Type<>(PlayerBlockStatus.id("enter_prefs"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryEnterPrefsPayload> STREAM_CODEC =
			StreamCodec.of(TerritoryEnterPrefsPayload::write, TerritoryEnterPrefsPayload::read);

	/** writeUtf 上限按字符计。 */
	private static final int MESSAGE_UTF_MAX = EntityDisplayNames.TERRITORY_NAME_MAX_LENGTH;
	private static final int INFOTYPE_UTF_MAX = 32;

	private static void write(RegistryFriendlyByteBuf buf, TerritoryEnterPrefsPayload payload) {
		buf.writeUtf(payload.ownEnterMessage, MESSAGE_UTF_MAX);
		buf.writeUtf(payload.infoType, INFOTYPE_UTF_MAX);
	}

	private static TerritoryEnterPrefsPayload read(RegistryFriendlyByteBuf buf) {
		return new TerritoryEnterPrefsPayload(
				buf.readUtf(MESSAGE_UTF_MAX),
				buf.readUtf(INFOTYPE_UTF_MAX)
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
