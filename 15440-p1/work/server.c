
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include "dirtree.h"
#include <dlfcn.h>


#define BACKLOG 10 // max 10 clients at once

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

typedef int (*orig_open_type)(const char *, int, ...);
typedef ssize_t (*orig_read_type)(int, void *, size_t);
typedef ssize_t (*orig_write_type)(int, const void *, size_t);
typedef int (*orig_close_type)(int);
typedef off_t (*orig_lseek_type)(int, off_t, int);
typedef int (*orig_stat_type)(const char *, struct stat *);
typedef int (*orig_unlink_type)(const char *);
typedef ssize_t (*orig_getdirentries_type)(int, char*, size_t, off_t*);
typedef struct dirtreenode *(*orig_getdirtree_type)(const char *);

int sockfd;


//helper
static int robust_send_server(int sockfd, const void *buf, size_t len) {
    size_t total = 0;
    const char *ptr = buf;
    while (total < len) {
        ssize_t n = send(sockfd, ptr + total, len - total, 0);
        if (n <= 0)
            return -1;
        total += n;
    }
    return total;
}

static void send_error(int sockfd, int error_code) {
    int32_t response[2];
    response[0] = htonl(-1);
    response[1] = htonl(error_code);
    robust_send_server(sockfd, response, sizeof(response));
}

static void send_success(int sockfd, int32_t result) {
    int32_t response = htonl(result);
    robust_send_server(sockfd, &response, sizeof(response));
}

// rpc handler 

static void handle_open(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 12) {
        send_error(sessfd, EINVAL);
        return;
    }
    uint32_t path_len = ntohl(*(uint32_t *)payload);
    int flags = ntohl(*(uint32_t *)(payload + 4));
    mode_t mode = ntohl(*(uint32_t *)(payload + 8));
    const char *path = payload + 12;
    if (12 + path_len > payload_len) {
        send_error(sessfd, EINVAL);
        return;
    }

    orig_open_type orig_open = (orig_open_type)dlsym(RTLD_NEXT, "open");
    int fd = orig_open(path, flags, mode);

    if (fd < 0)
        send_error(sessfd, errno);
    else
        send_success(sessfd, fd);
}

static void handle_close(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 4) {
        send_error(sessfd, EINVAL);
        return;
    }
    int fd = ntohl(*(uint32_t *)payload);

    orig_close_type orig_close = (orig_close_type)dlsym(RTLD_NEXT, "close");
    int result = orig_close(fd);

    if (result < 0)
        send_error(sessfd, errno);
    else
        send_success(sessfd, result);
}

static void handle_read(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 8) {
        send_error(sessfd, EINVAL);
        return; 
    }
    int fd = ntohl(*(uint32_t *)payload);
    size_t count = ntohl(*(uint32_t *)(payload + 4));
    char *buffer = malloc(count);
    if (!buffer) {
        send_error(sessfd, ENOMEM);
        return;
    }

    orig_read_type orig_read = (orig_read_type)dlsym(RTLD_NEXT, "read");
    ssize_t bytes_read = orig_read(fd, buffer, count);

    if (bytes_read < 0)
        send_error(sessfd, errno);
    else {
        send_success(sessfd, bytes_read);
        if (bytes_read > 0)
            robust_send_server(sessfd, buffer, bytes_read);
    }
    free(buffer);
}

static void handle_write(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 8) {
        send_error(sessfd, EINVAL);
        return;
    }
    int fd = ntohl(*(uint32_t *)payload);
    size_t count = ntohl(*(uint32_t *)(payload + 4));
    const char *data = payload + 8;
    if (8 + count > payload_len) {
        send_error(sessfd, EINVAL);
        return;
    }

    orig_write_type orig_write = (orig_write_type)dlsym(RTLD_NEXT, "write");

    ssize_t total_written = 0;
    ssize_t bytes_written; 
    const char *data_ptr = data;
    while (total_written < count) {
        bytes_written = orig_write(fd, data_ptr + total_written, count - total_written);
        if (bytes_written < 0) {
            send_error(sessfd, errno);
            return;
        }
        total_written += bytes_written;
    }
    send_success(sessfd, total_written);
}

static void handle_lseek(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 16) {
        send_error(sessfd, EINVAL);
        return;
    }
    int fd = ntohl(*(uint32_t *)payload);
    off_t offset = be64toh(*(uint64_t *)(payload + 4));
    int whence = ntohl(*(uint32_t *)(payload + 12));

    orig_lseek_type orig_lseek = (orig_lseek_type)dlsym(RTLD_NEXT, "lseek");
    off_t result = orig_lseek(fd, offset, whence);

    if (result < 0) {
        send_error(sessfd, errno);
    } else {
        // Send a 4-byte success code  first
        int32_t success = 0;
        success = htonl(success);
        if (robust_send_server(sessfd, &success, sizeof(success)) != sizeof(success))
            return;
        // Now send the 8-byte off_t result.
        uint64_t net_result = htobe64(result);
        robust_send_server(sessfd, &net_result, sizeof(net_result));
    }
}

static void handle_stat(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 4) {
        send_error(sessfd, EINVAL);
        return;
    }
    uint32_t path_len = ntohl(*(uint32_t *)payload);
    const char *path = payload + 4;
    if (4 + path_len > payload_len) {
        send_error(sessfd, EINVAL);
        return;
    }
    struct stat st;

    orig_stat_type orig_stat = (orig_stat_type)dlsym(RTLD_NEXT, "stat");
    int result = orig_stat(path, &st);

    if (result < 0)
        send_error(sessfd, errno);
    else {
        send_success(sessfd, 0);
        robust_send_server(sessfd, &st, sizeof(st));
    }
}

static void handle_unlink(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 4) {
        send_error(sessfd, EINVAL);
        return;
    }
    uint32_t path_len = ntohl(*(uint32_t *)payload);
    const char *path = payload + 4;
    if (4 + path_len > payload_len) {
        send_error(sessfd, EINVAL);
        return;
    }

    orig_unlink_type orig_unlink = (orig_unlink_type)dlsym(RTLD_NEXT, "unlink");
    int result = orig_unlink(path);
    if (result < 0)
        send_error(sessfd, errno);
    else
        send_success(sessfd, result);
}

static void handle_getdirentries(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 16) {
        send_error(sessfd, EINVAL);
        return;
    }
    int fd = ntohl(*(uint32_t *)payload);
    size_t nbytes = ntohl(*(uint32_t *)(payload + 4));
    off_t basep = be64toh(*(uint64_t *)(payload + 8));
    char *buffer = malloc(nbytes);
    if (!buffer) {
        send_error(sessfd, ENOMEM);
        return;
    }

    orig_getdirentries_type orig_getdirentries = (orig_getdirentries_type)dlsym(RTLD_NEXT, "getdirentries");
    ssize_t bytes_read = orig_getdirentries(fd, buffer, nbytes, &basep);
    if (bytes_read < 0)
        send_error(sessfd, errno);
    else {
        send_success(sessfd, bytes_read);
        if (bytes_read > 0) {
            robust_send_server(sessfd, buffer, bytes_read);
            uint64_t net_base = htobe64(basep);
            robust_send_server(sessfd, &net_base, sizeof(net_base));
        }
    }
    free(buffer);
}

// dir tree helper

static size_t calculate_tree_size(struct dirtreenode *node) {
    if (!node)
        return 0;
    size_t size = 4 + strlen(node->name) + 1 + 4; // name length, name, num_subdirs
    for (int i = 0; i < node->num_subdirs; i++)
        size += calculate_tree_size(node->subdirs[i]);
    return size;
}

static char* serialize_tree(struct dirtreenode *node, char *buffer) {
    if (!node)
        return buffer;
    uint32_t name_len = strlen(node->name) + 1;
    *(uint32_t *)buffer = htonl(name_len);
    buffer += 4;
    strcpy(buffer, node->name);
    buffer += name_len;
    *(uint32_t *)buffer = htonl(node->num_subdirs);
    buffer += 4;
    for (int i = 0; i < node->num_subdirs; i++)
        buffer = serialize_tree(node->subdirs[i], buffer);
    return buffer;
}

static void handle_getdirtree(int sessfd, const char *payload, size_t payload_len) {
    if (payload_len < 4) {
        send_error(sessfd, EINVAL);
        return;
    }
    uint32_t path_len = ntohl(*(uint32_t *)payload);
    const char *path = payload + 4;
    if (4 + path_len > payload_len) {
        send_error(sessfd, EINVAL);
        return;
    }

    orig_getdirtree_type orig_getdirtree = (orig_getdirtree_type)dlsym(RTLD_NEXT, "getdirtree");
    struct dirtreenode *root = orig_getdirtree(path);
    if (!root) {
        send_error(sessfd, errno);
        return;
    }
    size_t tree_size = calculate_tree_size(root);
    uint32_t net_size = htonl(tree_size);
    robust_send_server(sessfd, &net_size, sizeof(net_size));
    char *buffer = malloc(tree_size);
    if (buffer) {
        serialize_tree(root, buffer);
        robust_send_server(sessfd, buffer, tree_size);
        free(buffer);
    }
    freedirtree(root);
}

//client handling

static void handle_client(int sessfd) {
    while (1) {
        uint32_t msg_len;
        ssize_t rv = recv(sessfd, &msg_len, sizeof(msg_len), 0);
        if (rv <= 0)
            break;
        msg_len = ntohl(msg_len);
        char *msg = malloc(msg_len);
        if (!msg)
            break;
        size_t bytes_received = 0;
        while (bytes_received < msg_len) {
            rv = recv(sessfd, msg + bytes_received, msg_len - bytes_received, 0);
            if (rv <= 0) {
                free(msg);
                return;
            }
            bytes_received += rv;
        }
        uint8_t op_code = msg[0];
        const char *payload = msg + 1;
        size_t payload_len = msg_len - 1;
        switch (op_code) {
            case OPEN_CODE:
                handle_open(sessfd, payload, payload_len);
                break;
            case CLOSE_CODE:
                handle_close(sessfd, payload, payload_len);
                break;
            case READ_CODE:
                handle_read(sessfd, payload, payload_len);
                break;
            case WRITE_CODE:
                handle_write(sessfd, payload, payload_len);
                break;
            case LSEEK_CODE:
                handle_lseek(sessfd, payload, payload_len);
                break;
            case STAT_CODE:
                handle_stat(sessfd, payload, payload_len);
                break;
            case UNLINK_CODE:
                handle_unlink(sessfd, payload, payload_len);
                break;
            case GETDIRENTRIES_CODE:
                handle_getdirentries(sessfd, payload, payload_len);
                break;
            case GETDIRTREE_CODE:
                handle_getdirtree(sessfd, payload, payload_len);
                break;
            default:
                send_error(sessfd, EINVAL);
        }
        free(msg);
    }
}


int main(int argc, char **argv) {
    const char *port_str = getenv("serverport15440");
    unsigned short port = port_str ? atoi(port_str) : 15440;

    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        perror("socket");
        exit(EXIT_FAILURE);
    }
    int opt = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);

    if (bind(sockfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        exit(EXIT_FAILURE);
    }
    if (listen(sockfd, BACKLOG) < 0) {
        perror("listen");
        exit(EXIT_FAILURE);
    }

    signal(SIGPIPE, SIG_IGN);

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int sessfd = accept(sockfd, (struct sockaddr *)&client_addr, &client_len);
        if (sessfd < 0) {
            perror("accept");
            continue;
        }
        pid_t pid = fork();
        if (pid < 0) {
            perror("fork");
            close(sessfd);
            continue;
        }
        if (pid == 0) {  // Child process handles the client session
            close(sockfd);
            handle_client(sessfd);
            close(sessfd);
            exit(EXIT_SUCCESS);
        } else {  // Parent process
            close(sessfd);
        }
    }
    close(sockfd);
    return 0;
}
