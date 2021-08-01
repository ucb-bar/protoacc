#include "rocc.h"
#include <string>

#define PROTOACC_OPCODE 2
#define FUNCT_SFENCE 0
#define FUNCT_PROTO_PARSE_INFO 1
#define FUNCT_DO_PROTO_PARSE 2
#define FUNCT_MEM_SETUP 3
#define FUNCT_CHECK_COMPLETION 4

#define PROTOACC_SER_OPCODE 3
#define FUNCT_SER_SFENCE 0
#define FUNCT_HASBITS_INFO 1
#define FUNCT_DO_PROTO_SERIALIZE 2
#define FUNCT_SER_MEM_SETUP 3
#define FUNCT_SER_CHECK_COMPLETION 4

void AccelSetup();
volatile char ** AccelSetupSerializer();

#define AccelParseFromString(filename, msgtype, dest, inputstr) \
    AccelParseFromString_Helper(filename##_FriendStruct_##msgtype##_ACCEL_DESCRIPTORS::msgtype##_ACCEL_DESCRIPTORS, \
        dest, inputstr);

void AccelParseFromString_Helper(const void * descriptor_table_ptr, void * dest_base_addr,
                          const std::string* inputstr);

#define AccelSerializeToString(filename, msgtype, src) \
    AccelSerializeToString_Helper(filename##_FriendStruct_##msgtype##_ACCEL_DESCRIPTORS::msgtype##_ACCEL_DESCRIPTORS, \
        src);

void AccelSerializeToString_Helper(const void * descriptor_table_ptr, void * src_base_addr);

uint64_t block_on_completion();

volatile char * BlockOnSerializedValue(volatile char ** ptrs, int index);
size_t GetSerializedLength(volatile char ** ptrs, int index);
