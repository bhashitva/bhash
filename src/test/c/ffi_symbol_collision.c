/* SPDX-License-Identifier: Apache-2.0 */
/* Stand-ins for an incompatible system/vendor zlib linked by another library.
 * Compression must use its versioned private symbols, never these functions. */
int deflate(void) { return -99; }
int inflate(void) { return -99; }
