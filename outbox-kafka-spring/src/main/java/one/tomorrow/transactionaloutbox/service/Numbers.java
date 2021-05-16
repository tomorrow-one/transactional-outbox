package one.tomorrow.transactionaloutbox.service;

public class Numbers {

	/**
	 * @deprecated Use {@link one.tomorrow.kafka.core.Longs#toByteArray(long)} instead.
	 */
	@Deprecated(forRemoval = true)
	public static byte[] toByteArray(long data) {
		return new byte[] {
				(byte) (data >>> 56),
				(byte) (data >>> 48),
				(byte) (data >>> 40),
				(byte) (data >>> 32),
				(byte) (data >>> 24),
				(byte) (data >>> 16),
				(byte) (data >>> 8),
				(byte) data
		};
	}

	/**
	 * @deprecated Use {@link one.tomorrow.kafka.core.Longs#toLong(byte[])} instead.
	 */
	@Deprecated(forRemoval = true)
	public static long toLong(byte[] data) {
		if (data == null || data.length != 8) {
			throw new IllegalArgumentException("Size of data received is not 8");
		}

		long value = 0;
		for (byte b : data) {
			value <<= 8;
			value |= b & 0xFF;
		}
		return value;
	}

}
