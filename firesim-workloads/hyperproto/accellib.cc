#include "accellib.h"
#include <cassert>
#include <malloc.h>

#define PAGESIZE_BYTES 4096

void AccelSetupFixedAllocRegion() {
    ROCC_INSTRUCTION(PROTOACC_OPCODE, FUNCT_SFENCE);

    size_t regionsize = sizeof(char) * (128 << 17);
    char * fixed_alloc_region = (char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        fixed_alloc_region[i] = 0;
    }

    char * array_alloc_region = (char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        array_alloc_region[i] = 0;
    }

    uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;
    uint64_t array_ptr_as_int = (uint64_t)array_alloc_region;

    ROCC_INSTRUCTION_SS(PROTOACC_OPCODE, fixed_ptr_as_int, array_ptr_as_int, FUNCT_MEM_SETUP);
    assert((fixed_ptr_as_int & 0x7) == 0x0);
    assert((array_ptr_as_int & 0x7) == 0x0);

    printf("accelerator given %lld byte region, starting at 0x%016llx for fixed alloc\n", (uint64_t)regionsize, fixed_ptr_as_int);
    printf("accelerator given %lld byte region, starting at 0x%016llx for array alloc\n", (uint64_t)regionsize, array_ptr_as_int);

}

void AccelSetup() {
    AccelSetupFixedAllocRegion();
}

volatile char ** AccelSetupFixedAllocRegionSerializer() {
    ROCC_INSTRUCTION(PROTOACC_SER_OPCODE, FUNCT_SER_SFENCE);

    size_t regionsize = sizeof(char) * (128 << 16);
    char * string_alloc_region = (char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        string_alloc_region[i] = 0;
    }

    uint64_t stringalloc_region_ptr_as_int = (uint64_t)string_alloc_region;
    uint64_t stringalloc_region_ptr_as_int_tail = stringalloc_region_ptr_as_int + (uint64_t)regionsize;

    uint64_t num_string_ptrs = 2048;
    size_t string_ptr_region_size = num_string_ptrs * sizeof(char*);
    char ** stringptr_region = (char**)memalign(PAGESIZE_BYTES, string_ptr_region_size);

    char * stringptrcharwriter = (char*)stringptr_region;
    for (uint64_t i = 0; i < string_ptr_region_size; i += PAGESIZE_BYTES) {
        stringptrcharwriter[i] = 0;
    }
    stringptr_region[0] = (char*)stringalloc_region_ptr_as_int_tail;
    stringptr_region += 1;

    uint64_t string_ptr_region_ptr_as_int = (uint64_t)stringptr_region;

    ROCC_INSTRUCTION_SS(PROTOACC_SER_OPCODE, stringalloc_region_ptr_as_int_tail, string_ptr_region_ptr_as_int, FUNCT_SER_MEM_SETUP);
    assert((stringalloc_region_ptr_as_int_tail & 0x7) == 0x0);
    assert((string_ptr_region_ptr_as_int & 0x7) == 0x0);

    printf("accelerator given %lld byte region, tail at 0x%016llx for string alloc\n", (uint64_t)regionsize, stringalloc_region_ptr_as_int_tail);
    printf("accelerator given %lld byte region, starting at 0x%016llx for string ptr alloc\n", (uint64_t)string_ptr_region_size, string_ptr_region_ptr_as_int);

    return (volatile char**)stringptr_region;
}

volatile char ** AccelSetupSerializer() {
    return AccelSetupFixedAllocRegionSerializer();
}

volatile char * BlockOnSerializedValue(volatile char ** ptrs, int index) {
    uint64_t retval;
    ROCC_INSTRUCTION_D(PROTOACC_SER_OPCODE, retval, FUNCT_SER_CHECK_COMPLETION);
    asm volatile ("fence");

    int i = 0;
    while (ptrs[index] == 0) {
        asm volatile ("fence");
    }
    return ptrs[index];
}

size_t GetSerializedLength(volatile char ** ptrs, int index) {
    return (size_t)(ptrs[index-1] - ptrs[index]);
}

void AccelParseFromString_Helper(const void * descriptor_table_ptr, void * dest_base_addr,
                          const std::string* inputstr) {
    const void * base_ptr = inputstr->c_str();
    uint64_t input_length = inputstr->length();
    if (input_length == 0) {
        return;
    }

    uint64_t* access_descr_ptr = (uint64_t*)descriptor_table_ptr;
    uint64_t min_field_no = access_descr_ptr[3] >> 32;
    uint64_t low32_mask_internal = 0x00000000FFFFFFFFL;
    uint64_t min_field_no_and_input_length = (min_field_no << 32) | (input_length & low32_mask_internal);

    ROCC_INSTRUCTION_SS(PROTOACC_OPCODE, descriptor_table_ptr, dest_base_addr, FUNCT_PROTO_PARSE_INFO);
    ROCC_INSTRUCTION_SS(PROTOACC_OPCODE, base_ptr, min_field_no_and_input_length, FUNCT_DO_PROTO_PARSE);

}

uint64_t block_on_completion() {
    uint64_t retval;
    ROCC_INSTRUCTION_D(PROTOACC_OPCODE, retval, FUNCT_CHECK_COMPLETION);
    asm volatile ("fence");
    return retval;
}

void AccelSerializeToString_Helper(const void * descriptor_table_ptr, void * src_base_addr) {
    uint64_t* access_descr_ptr = (uint64_t*)descriptor_table_ptr;
    uint64_t hasbits_offset = access_descr_ptr[2];
    uint64_t min_max_fieldno = access_descr_ptr[3];

    ROCC_INSTRUCTION_SS(PROTOACC_SER_OPCODE, hasbits_offset, min_max_fieldno, FUNCT_HASBITS_INFO);
    ROCC_INSTRUCTION_SS(PROTOACC_SER_OPCODE, descriptor_table_ptr, src_base_addr, FUNCT_DO_PROTO_SERIALIZE);
}

