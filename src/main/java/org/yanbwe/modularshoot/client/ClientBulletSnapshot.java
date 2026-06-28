package org.yanbwe.modularshoot.client;

/**
 * <strong>Deprecated — moved to
 * {@link org.yanbwe.modularshoot.network.ClientBulletSnapshot}.</strong>
 *
 * <p>This class was relocated from the {@code client} package to the
 * {@code network} package (common layer) to fix W24: the snapshot is
 * embedded inside {@code BulletS2CPacket.FullBulletEntry} and serialised by
 * {@code BulletSyncService}, both common-layer network classes. Keeping it
 * in the {@code client} package violated the layer-separation rule because
 * common-layer code referenced a client-package class.</p>
 *
 * <p>This file is intentionally left empty (no class definition) to avoid a
 * duplicate type. All references should point to
 * {@code org.yanbwe.modularshoot.network.ClientBulletSnapshot}.</p>
 *
 * @see org.yanbwe.modularshoot.network.ClientBulletSnapshot
 */
