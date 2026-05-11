/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.lucene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Raw little-endian float32 encoding for embedding bytes. Same shape as the MySQL backend's vector
 * codec — chartsearchai uses the identical encoding on its {@code chartsearchai_embedding}
 * column. Lucene's {@code KnnFloatVectorField} carries the vector for kNN search; this codec
 * backs a parallel {@code StoredField} so callers can read the embedding back from a retrieved
 * document (stored values are not exposed by the kNN field itself).
 */
final class LuceneVectorCodec {

	private LuceneVectorCodec() {
	}

	static byte[] encode(float[] vector) {
		if (vector == null) {
			return null;
		}
		ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		buf.asFloatBuffer().put(vector);
		return buf.array();
	}

	/**
	 * Decode a {@code float[]} from a {@code byte[]} window — Lucene's {@code BytesRef} returns a
	 * backing array that can be longer than the actual stored content, so callers always pass the
	 * offset and length explicitly.
	 */
	static float[] decode(byte[] bytes, int offset, int length) {
		if (bytes == null) {
			return null;
		}
		float[] out = new float[length / 4];
		ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
		return out;
	}
}
