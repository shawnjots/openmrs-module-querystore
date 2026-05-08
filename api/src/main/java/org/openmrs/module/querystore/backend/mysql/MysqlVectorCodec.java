/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.mysql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Encodes a {@code float[]} as raw little-endian float32 bytes (4 bytes per dimension, no length
 * header). Same encoding chartsearchai uses on its {@code chartsearchai_embedding.embedding}
 * column. Length is recoverable as {@code bytes.length / 4}.
 */
final class MysqlVectorCodec {

	private MysqlVectorCodec() {
	}

	static byte[] encode(float[] vector) {
		if (vector == null) {
			return null;
		}
		ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		buf.asFloatBuffer().put(vector);
		return buf.array();
	}

	static float[] decode(byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		float[] out = new float[bytes.length / 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
		return out;
	}

	/** L2 norm of a query vector. Cache once per kNN scan rather than per row. */
	static double norm(float[] vector) {
		if (vector == null) {
			return 0.0;
		}
		double sumSq = 0.0;
		for (float v : vector) {
			sumSq += v * v;
		}
		return Math.sqrt(sumSq);
	}

	/**
	 * Cosine similarity between {@code query} and a stored vector held in raw little-endian float32
	 * bytes. {@code queryNorm} must equal {@link #norm(float[])} of {@code query}; pre-computing it
	 * once per kNN scan rather than per row saves ~half the multiply-adds on a 100k-row corpus
	 * (~76M ops) and avoids materialising the stored vector as a {@code float[]} on the hot path.
	 */
	static double cosineFromBytes(float[] query, double queryNorm, byte[] storedBytes) {
		if (query == null || queryNorm == 0.0 || storedBytes == null || storedBytes.length / 4 != query.length) {
			return 0.0;
		}
		FloatBuffer stored = ByteBuffer.wrap(storedBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
		double dot = 0.0;
		double nb = 0.0;
		for (int i = 0; i < query.length; i++) {
			float b = stored.get(i);
			dot += query[i] * b;
			nb += b * b;
		}
		if (nb == 0.0) {
			return 0.0;
		}
		return dot / (queryNorm * Math.sqrt(nb));
	}
}
