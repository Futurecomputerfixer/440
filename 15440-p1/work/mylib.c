#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h> 
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include "dirtree.h"

/* RPC operation codes */ 
#define OPEN_CODE           0x01
#define CLOSE_CODE          0x02
#define READ_CODE           0x03
#define WRITE_CODE          0x04
#define LSEEK_CODE          0x05
#define STAT_CODE           0x06
#define UNLINK_CODE         0x07
#define GETDIRENTRIES_CODE  0x08
#define GETDIRTREE_CODE     0x09

typedef ssize_t (*orig_read_type)(int, void *, size_t);
typedef ssize_t (*orig_write_type)(int, const void *, size_t);
typedef int (*orig_close_type)(int);
typedef void (*orig_freedirtree_type)(struct dirtreenode *);

// FD Mapping (Local <-> Remote)
typedef struct {
    int local_fd;
    int remote_fd;
} FdMapping;

static FdMapping *fd_table = NULL;
static int fd_table_size = 0;
static int next_local_fd = 1000;

int sockfd;

/* Returns the remote file descriptor corresponding to local_fd */
static int get_remote_fd(int local_fd) {
    for (int i = 0; i < fd_table_size; i++) {
        if (fd_table[i].local_fd == local_fd)
            return fd_table[i].remote_fd;
    }

    errno = EBADF;
    return -1;
}

/* Adds a new mapping from local_fd to remote_fd */
static void add_fd_mapping(int local_fd, int remote_fd) {
    fd_table = realloc(fd_table, (fd_table_size + 1) * sizeof(FdMapping));
    fd_table[fd_table_size].local_fd = local_fd;
    fd_table[fd_table_size].remote_fd = remote_fd;
    fd_table_size++;
}

/* Removes the mapping for local_fd */
static void remove_fd_mapping(int local_fd) {
    for (int i = 0; i < fd_table_size; i++) {
        if (fd_table[i].local_fd == local_fd) {
            memmove(&fd_table[i], &fd_table[i + 1],
                    (fd_table_size - i - 1) * sizeof(FdMapping));
            fd_table_size--;
            return;
        }
    }
}


//   RPC Communication Helpers

static int robust_send(int sockfd, const void *buf, size_t len) {
    size_t total = 0;
    const char *ptr = buf;
    while (total < len) {
        ssize_t n = send(sockfd, ptr + total, len - total, 0);
        if (n <= 0)
            return -1;  // or handle EINTR etc.
        total += n;
    }
    return total;
}

/* Sends an RPC request.
 */
static int send_rpc_request(uint8_t code, const void *data, size_t len) {
    uint32_t net_len = htonl(len + 1);  // +1 for the op code
    if (robust_send(sockfd, &net_len, sizeof(net_len)) != sizeof(net_len))
        return -1;
    if (robust_send(sockfd, &code, 1) != 1)
        return -1;
    if (len > 0 && robust_send(sockfd, data, len) != (ssize_t)len)
        return -1;
    return 0;
}

/* robust_recv: read exactly len bytes (looping if needed) */
static int robust_recv(int sockfd, void *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t n = recv(sockfd, (char *)buf + total, len - total, 0);
        if (n <= 0)
            return -1;
        total += n;
    }
    return total;
}

/* Receives an RPC response.
 */
static int receive_rpc_response(void *response_data, size_t *response_len) {
    int32_t result, error;
    if (robust_recv(sockfd, &result, sizeof(result)) != sizeof(result))
        return -1;
    result = ntohl(result);

    if (result < 0) {
        if (robust_recv(sockfd, &error, sizeof(error)) != sizeof(error))
            return -1;
        errno = ntohl(error);
    } else if (response_data && response_len) {
        size_t to_receive = (*response_len < (size_t)result) ? *response_len : result;
        if (robust_recv(sockfd, response_data, to_receive) != (ssize_t)to_receive)
            return -1;
        *response_len = to_receive;
    }
    return result;
}


int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }
    char payload[1024];
    uint32_t *header = (uint32_t *)payload;
    header[0] = htonl(strlen(path) + 1);
    header[1] = htonl(flags);
    header[2] = htonl(mode);
    strcpy(payload + 12, path);

    if (send_rpc_request(OPEN_CODE, payload, 12 + strlen(path) + 1) < 0)
        return -1;

    int remote_fd = receive_rpc_response(NULL, NULL);
    if (remote_fd < 0)
        return -1;

    int local_fd = next_local_fd++;
    add_fd_mapping(local_fd, remote_fd);
    return local_fd;
}

int close(int fd) {
    int remote_fd = get_remote_fd(fd);
    if (remote_fd < 0)
    {
        if (remote_fd < 0) {

        orig_close_type orig_close = (orig_close_type)dlsym(RTLD_NEXT, "close");
        return orig_close(fd);
    }

    }
    uint32_t payload = htonl(remote_fd);
    if (send_rpc_request(CLOSE_CODE, &payload, sizeof(payload)) < 0)
        return -1;
    int result = receive_rpc_response(NULL, NULL);
    if (result == 0)
        remove_fd_mapping(fd);
    return result;
}

ssize_t read(int fd, void *buf, size_t count) {
    int remote_fd = get_remote_fd(fd);
    if (remote_fd < 0)
    {
        orig_read_type orig_read = (orig_read_type)dlsym(RTLD_NEXT, "read");
        return orig_read(fd, buf, count);
    }
    char payload[8];
    *(uint32_t *)payload = htonl(remote_fd);
    *(uint32_t *)(payload + 4) = htonl(count);
    if (send_rpc_request(READ_CODE, payload, sizeof(payload)) < 0)
        return -1;
    size_t response_len = count;
    return receive_rpc_response(buf, &response_len);
}

ssize_t write(int fd, const void *buf, size_t count) {
    int remote_fd = get_remote_fd(fd);
    if (remote_fd < 0)
    {
        orig_write_type orig_write = (orig_write_type)dlsym(RTLD_NEXT, "write");
        return orig_write(fd, buf, count);
    }

    char *payload = malloc(8 + count);
    if (!payload)
        return -1;
    *(uint32_t *)payload = htonl(remote_fd);
    *(uint32_t *)(payload + 4) = htonl(count);
    memcpy(payload + 8, buf, count);
    int result = -1;
    if (send_rpc_request(WRITE_CODE, payload, 8 + count) >= 0)
        result = receive_rpc_response(NULL, NULL);
    free(payload);
    return result;
}

off_t lseek(int fd, off_t offset, int whence) {
    int remote_fd = get_remote_fd(fd);
    if (remote_fd < 0) {
        errno = EBADF;
        return -1;
    }
    char payload[16];
    *(uint32_t *)payload = htonl(remote_fd);
    *(uint64_t *)(payload + 4) = htobe64(offset);
    *(uint32_t *)(payload + 12) = htonl(whence);
    if (send_rpc_request(LSEEK_CODE, payload, sizeof(payload)) < 0)
        return -1;


    int32_t result_code; 
    if (robust_recv(sockfd, &result_code, sizeof(result_code)) != sizeof(result_code))
        return -1;
    result_code = ntohl(result_code);
    if (result_code < 0) {
         int32_t err;
         if (robust_recv(sockfd, &err, sizeof(err)) != sizeof(err))
              return -1;
         errno = ntohl(err);
         return -1;
    }


    uint64_t net_off;
    if (robust_recv(sockfd, &net_off, sizeof(net_off)) != sizeof(net_off))
         return -1;
    return be64toh(net_off);
}

int stat(const char *path, struct stat *st) {
    uint32_t path_len = strlen(path) + 1;
    char *payload = malloc(4 + path_len);
    if (!payload)
        return -1;
    *(uint32_t *)payload = htonl(path_len);
    strcpy(payload + 4, path);
    int result = -1;
    if (send_rpc_request(STAT_CODE, payload, 4 + path_len) >= 0) {
        char stat_buf[512];
        size_t response_len = sizeof(stat_buf);
        result = receive_rpc_response(stat_buf, &response_len);
        if (result == 0)
            memcpy(st, stat_buf, sizeof(struct stat));
    }
    free(payload);
    return result;
}

int unlink(const char *path) {
    uint32_t path_len = strlen(path) + 1;
    char *payload = malloc(4 + path_len);
    if (!payload)
        return -1;
    *(uint32_t *)payload = htonl(path_len);
    strcpy(payload + 4, path);
    int result = -1;
    if (send_rpc_request(UNLINK_CODE, payload, 4 + path_len) >= 0)
        result = receive_rpc_response(NULL, NULL);
    free(payload);
    return result;
}

ssize_t getdirentries(int fd, char *buf, size_t nbytes, off_t *basep) {
    int remote_fd = get_remote_fd(fd);
    if (remote_fd < 0)
        return -1;
    char payload[16];
    *(uint32_t *)payload = htonl(remote_fd);
    *(uint32_t *)(payload + 4) = htonl(nbytes);
    *(uint64_t *)(payload + 8) = htobe64(*basep);
    if (send_rpc_request(GETDIRENTRIES_CODE, payload, sizeof(payload)) < 0)
        return -1;
    size_t response_len = nbytes;
    ssize_t result = receive_rpc_response(buf, &response_len);
    if (result > 0) {
        off_t new_base;
        if (recv(sockfd, &new_base, sizeof(new_base), 0) == sizeof(new_base))
            *basep = be64toh(new_base);
    }
    return result;
}

static struct dirtreenode* deserialize_tree(char **buffer_ptr, size_t *remaining) {
    if (*remaining < 4) return NULL;
    uint32_t name_len = ntohl(*(uint32_t *)(*buffer_ptr));
    *buffer_ptr += 4; *remaining -= 4;

    if (*remaining < name_len) return NULL;
    char *name = strndup(*buffer_ptr, name_len - 1); 
    *buffer_ptr += name_len; *remaining -= name_len;

    if (*remaining < 4) { free(name); return NULL; }
    uint32_t num_subdirs = ntohl(*(uint32_t *)(*buffer_ptr));
    *buffer_ptr += 4; *remaining -= 4;

    struct dirtreenode *node = malloc(sizeof(struct dirtreenode));
    node->name = name;
    node->num_subdirs = num_subdirs;
    node->subdirs = malloc(num_subdirs * sizeof(struct dirtreenode *));

    for (int i = 0; i < num_subdirs; i++) {
        node->subdirs[i] = deserialize_tree(buffer_ptr, remaining);
    }
    return node;
}

/* Interposed getdirtree: requests a serialized directory tree from the server,
 * then deserializes it using the helper above.
 */
struct dirtreenode* getdirtree(const char *path) {
    uint32_t path_len = strlen(path) + 1;
    char *payload = malloc(4 + path_len);
    if (!payload)
        return NULL;
    *(uint32_t *)payload = htonl(path_len);
    strcpy(payload + 4, path);
    struct dirtreenode *root = NULL;
    if (send_rpc_request(GETDIRTREE_CODE, payload, 4 + path_len) >= 0) {
        uint32_t net_result;
        if (robust_recv(sockfd, &net_result, sizeof(net_result)) != sizeof(net_result)) {
            free(payload);
            return NULL;
        }
        int32_t result_code = ntohl(net_result);
        if (result_code < 0) {
            int32_t net_err;
            if (robust_recv(sockfd, &net_err, sizeof(net_err)) != sizeof(net_err)) {
                free(payload);
                return NULL;
            }
            errno = ntohl(net_err);
            free(payload);
            return NULL;
        }
        uint32_t tree_size = (uint32_t)result_code;
        char *buffer = malloc(tree_size);
        if (buffer && robust_recv(sockfd, buffer, tree_size) == (ssize_t)tree_size) {
            size_t remaining = tree_size;
            char *buffer_ptr = buffer;
            root = deserialize_tree(&buffer_ptr, &remaining);
        }
        free(buffer);
    }
    free(payload);
    return root;
}

void freedirtree(struct dirtreenode *node) {
    orig_freedirtree_type orig_freedirtree = (orig_freedirtree_type)dlsym(RTLD_NEXT, "freedirtree");
    orig_freedirtree(node);
}

void _init(void) {
    const char *serverip = getenv("server15440") ? getenv("server15440") : "127.0.0.1";
    const char *port_str = getenv("serverport15440");
    unsigned short port = port_str ? atoi(port_str) : 15440;
    sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0)
        return;
    struct sockaddr_in srv_addr;
    memset(&srv_addr, 0, sizeof(srv_addr));
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_port = htons(port);
    srv_addr.sin_addr.s_addr = inet_addr(serverip);
    if (connect(sockfd, (struct sockaddr *)&srv_addr, sizeof(srv_addr)) < 0) {
        close(sockfd);
        sockfd = -1;
        return;
    }
}

