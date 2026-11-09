/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_app.h"
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#define VIEW(s) ((bhash_utf8_view){(const uint8_t *)(s), sizeof(s) - 1})

static void contains(bhash_handle store, int64_t filter, const char *fragment, int present) {
    bhash_bytes output = {0};
    assert(bhash_tasks_list(store, filter, &output, NULL) == BHASH_OK && output.len < 4096);
    char json[4096] = {0};
    memcpy(json, output.data, output.len);
    if ((strstr(json, fragment) != NULL) != present)
        fprintf(stderr, "filter=%" PRId64 " expected=%d fragment=%s snapshot=%s\n", filter, present, fragment, json);
    assert((strstr(json, fragment) != NULL) == present);
    bhash_bytes_free(&output);
}

int main(int argc, char **argv) {
    assert(argc == 3);
    assert(bhash_runtime_start(NULL) == BHASH_OK);
    bhash_handle store = 0;
    const bhash_utf8_view path = {(const uint8_t *)argv[2], strlen(argv[2])};
    bhash_error error = {0};
    const bhash_status opened = bhash_tasks_open(path, &store, &error);
    if (strcmp(argv[1], "reject") == 0) {
        assert(opened == BHASH_EXCEPTION && store == 0 && error.message.len);
        bhash_bytes_free(&error.message);
        assert(bhash_runtime_stop(NULL) == BHASH_OK);
        puts("unsupported schema rejected");
        return 0;
    }
    if (opened != BHASH_OK && error.message.data) fwrite(error.message.data, 1, error.message.len, stderr);
    bhash_bytes_free(&error.message);
    assert(opened == BHASH_OK);
    if (strcmp(argv[1], "create") == 0) {
        contains(store, 0, "किताब पढ़ें", 0);
        int64_t first, second, invalid;
        uint8_t found;
        assert(bhash_tasks_add(store, VIEW("   "), &invalid, NULL) == BHASH_OK && invalid == 0);
        assert(bhash_tasks_add(store, VIEW("पहला\0कार्य"), &invalid, NULL) == BHASH_OK && invalid == 0);
        assert(bhash_tasks_add(store, VIEW("पहला\xc2\x85" "कार्य"), &invalid, NULL) == BHASH_OK && invalid == 0);
        const bhash_status added = bhash_tasks_add(store, VIEW("  किताब पढ़ें😀  "), &first, &error);
        if (added != BHASH_OK && error.message.data) fwrite(error.message.data, 1, error.message.len, stderr);
        bhash_bytes_free(&error.message);
        if (added != BHASH_OK || first != 1) fprintf(stderr, "add status=%d id=%" PRId64 "\n", added, first);
        assert(added == BHASH_OK && first == 1);
        assert(bhash_tasks_add(store, VIEW("'; DROP TABLE tasks;--"), &second, NULL) == BHASH_OK && second == 2);
        assert(bhash_tasks_set_done(store, first, 1, &found, NULL) == BHASH_OK && found == 1);
        assert(bhash_tasks_set_done(store, 999, 1, &found, NULL) == BHASH_OK && found == 0);
        bhash_bytes invalid_list = {0};
        assert(bhash_tasks_list(store, -1, &invalid_list, NULL) == BHASH_EXCEPTION && !invalid_list.data);
    }
    contains(store, 0, "\"title\":\"किताब पढ़ें😀\"", 1);
    contains(store, 0, "'; DROP TABLE tasks;--", 1);
    contains(store, 1, "किताब पढ़ें", 0);
    contains(store, 2, "किताब पढ़ें", 1);
    contains(store, 2, "DROP TABLE", 0);
    assert(bhash_tasks_close(store, NULL) == BHASH_OK);
    assert(bhash_handle_release(store, NULL) == BHASH_OK);
    assert(bhash_runtime_stop(NULL) == BHASH_OK);
    puts("task list passed");
    return 0;
}
