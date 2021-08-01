#!/usr/bin/env python3

class PrimitiveInfo:
    def __init__(self, testname, primname, cpptype, testvals, is_repeated=False, nestedtype=None):
        self.testname = testname
        self.primname = primname
        self.cpptype = cpptype
        self.testvals = testvals
        self.is_repeated = is_repeated
        self.nestedtype = nestedtype
        if self.nestedtype is not None and self.is_repeated:
            exit(1)

    def get_test_name(self):
        return "ser_" + self.testname

    def generate_testvals_initialized(self):
        numtests = len(self.testvals)
        comma_separated_testvals = ", ".join(self.testvals)

        msg = """#define NUMTESTVALS {NUMTESTS}
{CPPTYPE} testvals[NUMTESTVALS] = {{  {INPUTVALS} }};
""".format(NUMTESTS=numtests, CPPTYPE=self.cpptype, INPUTVALS=comma_separated_testvals)
        return msg

    def get_fieldname_single_ident(self):
        if not self.is_repeated:
            return self.primname
        return self.primname + "_repeated"

    def get_nested_fieldname_single_ident(self):
        return self.nestedtype

    def get_fieldtype_spaces(self):
        if not self.is_repeated:
            return "optional " + self.primname
        return "repeated " + self.primname

    def get_message_name(self):
        return """Pacc{TESTNAME}Message""".format(TESTNAME=self.get_test_name())

    def get_allocator_line(self):
        msg = """primitivetests::{MESSAGENAME}* fillmessage = google::protobuf::Arena::CreateMessage<primitivetests::{MESSAGENAME}>(&arena);""".format(FIELDTYPE=self.get_fieldname_single_ident(), MESSAGENAME=self.get_message_name())
        msg2 = ""
        if self.nestedtype is not None:
            msg2 = """\n            primitivetests::Paccser_{FIELDTYPE}Message* nested_message = google::protobuf::Arena::CreateMessage<primitivetests::Paccser_{FIELDTYPE}Message>(&arena);""".format(FIELDTYPE=self.get_nested_fieldname_single_ident(), MESSAGENAME=self.get_message_name())
        return msg + msg2

    def get_setter_lines(self):
        numsets = len(self.testvals)
        retmsg = """"""
        for s in range(numsets):
            retmsg += "               " + self.get_setter_line(s) + "\n"
        return retmsg

    def get_setter_line(self, fieldindex):
        if self.is_repeated:
            msg = """fillmessage->add_pacc{FIELDTYPE}_{FIELDINDEX}(testvals[{FIELDINDEX}]);""".format(FIELDTYPE=self.get_fieldname_single_ident(), FIELDINDEX=fieldindex)
        elif self.nestedtype is not None:
            msg = """nested_message->set_pacc{NESTEDFIELDTYPE}_{FIELDINDEX}(testvals[{FIELDINDEX}]);
            fillmessage->set_allocated_pacc{FIELDTYPE}_{FIELDINDEX}(nested_message);
""".format(NESTEDFIELDTYPE=self.get_nested_fieldname_single_ident(),
                       FIELDTYPE=self.get_fieldname_single_ident().lower(),
           FIELDINDEX=fieldindex)

        else:
            msg = """fillmessage->set_pacc{FIELDTYPE}_{FIELDINDEX}(testvals[{FIELDINDEX}]);""".format(FIELDTYPE=self.get_fieldname_single_ident(), FIELDINDEX=fieldindex)

        return msg

    def get_getter(self, fieldindex):
        if self.is_repeated:
            msg = """pacc{FIELDTYPE}_{FIELDINDEX}(0)""".format(FIELDTYPE=self.get_fieldname_single_ident(), FIELDINDEX=fieldindex)
        elif self.nestedtype is not None:
            msg = """pacc{NESTEDFIELDTYPE}_{FIELDINDEX}().pacc{FIELDTYPE}_{FIELDINDEX}()""".format(NESTEDFIELDTYPE=self.get_fieldname_single_ident().lower(),
                                                                         FIELDTYPE=self.get_nested_fieldname_single_ident(), FIELDINDEX=fieldindex)
        else:
            msg = """pacc{FIELDTYPE}_{FIELDINDEX}()""".format(FIELDTYPE=self.get_fieldname_single_ident(), FIELDINDEX=fieldindex)

        return msg

    def gen_simple_message(self):
        msg = """
message {MESSAGENAME} {{
""".format(FIELDTYPE=self.get_fieldname_single_ident(), MESSAGENAME=self.get_message_name())
        for x in range(len(self.testvals)):
            msg += """    {PROPERFIELDTYPE} pacc{FIELDTYPE}_{FIELDINDEX} = {FIELDNO};
""".format(PROPERFIELDTYPE=self.get_fieldtype_spaces(), FIELDTYPE=self.get_fieldname_single_ident(),
           FIELDINDEX=x, FIELDNO=x+1)
        msg += """}"""
        return msg

gen_test_fields = 5

primitive_info_objs = [
    PrimitiveInfo("double", "double", "double", ["1.0" for _ in range(gen_test_fields)]),
    PrimitiveInfo("double_repeated", "double", "double", ["1.0"], is_repeated=True),
    PrimitiveInfo("PaccdoubleMessage", "Paccser_doubleMessage", "double", ["1.0"], is_repeated=False, nestedtype="double"),

    PrimitiveInfo("float", "float", "float", ["1.0" for _ in range(gen_test_fields)]),
    PrimitiveInfo("float_repeated", "float", "float", ["1.0"], is_repeated=True),

    PrimitiveInfo("fixed32", "fixed32", "uint32_t", ["1" for _ in range(gen_test_fields)]),
    PrimitiveInfo("fixed64", "fixed64", "uint64_t", ["1" for _ in range(gen_test_fields)]),
    PrimitiveInfo("sfixed32", "sfixed32", "int32_t", ["1" for _ in range(gen_test_fields)]),
    PrimitiveInfo("sfixed64", "sfixed64", "int64_t", ["1" for _ in range(gen_test_fields)]),
    PrimitiveInfo("bool", "bool", "bool", ["true" for _ in range(gen_test_fields)]),
    PrimitiveInfo("PaccboolMessage", "Paccser_boolMessage", "bool", ["true"], is_repeated=False, nestedtype="bool"),

    PrimitiveInfo("bool_repeated", "bool", "bool", ["true"], is_repeated=True),

    PrimitiveInfo("string", "string", "string", ['"hello"']),
    PrimitiveInfo("string_long", "string", "string", ['"hello hello hello hello hello hello hello"']),
    PrimitiveInfo("string_15", "string", "string", ['"hello hello he"']),
    PrimitiveInfo("string_very_long", "string", "string", ['"' + ("a" * 489) + '"']),

    PrimitiveInfo("PaccstringMessage", "Paccser_stringMessage", "string", ['"hello"'], is_repeated=False, nestedtype="string"),

    PrimitiveInfo("bytes", "bytes", "string", ['"hello"']),
    PrimitiveInfo("bytes_repeated", "bytes", "string", ['"hello"'], is_repeated=True),

    PrimitiveInfo("bytes_long", "bytes", "string", ['"hello hello hello hello hello hello hello"']),
    PrimitiveInfo("bytes_very_long", "bytes", "string", ['"' + ("a" * 489) + '"']),
    PrimitiveInfo("bytes_15", "bytes", "string", ['"hello hello he"']),
]

varintvals = ["0ULL", "1ULL", "128ULL", "16384ULL", "2097152ULL", "268435456ULL", "34359738368ULL", "4398046511104ULL", "562949953421312ULL", "72057594037927936ULL", "9223372036854775808ULL"]

for testind in range(len(varintvals)):
    testval = varintvals[testind]
    primitive_info_objs.append(PrimitiveInfo("uint64_size" + str(testind).zfill(2) + "B", "uint64", "uint64_t", [testval for _ in range(gen_test_fields)]))
    primitive_info_objs.append(PrimitiveInfo("uint64_size" + str(testind).zfill(2) + "B" + "_repeated", "uint64", "uint64_t", [testval for _ in range(gen_test_fields)], is_repeated=True))


for numfields in [1, 2, 3, 4, 5, 6, 7, 8, 16, 32, 64]:
    testval1B = varintvals[1]
    primitive_info_objs.append(PrimitiveInfo("uint64_size" + "01B" + "_fields_" + str(numfields), "uint64", "uint64_t", [testval1B for _ in range(numfields)]))

for numfields in [1, 2, 3, 4, 5, 6, 7, 8]:
    testval10B = varintvals[-1]
    primitive_info_objs.append(PrimitiveInfo("uint64_size" + "10B" + "_fields_" + str(numfields), "uint64", "uint64_t", [testval10B for _ in range(numfields)]))


def proto_file_header():
    header = """
syntax = "proto2";

package primitivetests;

option cc_enable_arenas = true;

"""
    return header


def build_proto_file(alltestobjs):
    contents = proto_file_header()

    checkdups = set()

    for testobj in alltestobjs:
        protocontent = testobj.gen_simple_message()
        if protocontent not in checkdups:
            contents += protocontent
            checkdups.add(protocontent)

    writeme = open(protoout, "w")
    writeme.write(contents)
    writeme.close()



def cpp_file_header():
    header = """
#include <iostream>
#include <fstream>
#include <string>
#include <cstdio>
#include <cinttypes>
#include <chrono>

#include "primitives.pb.h"

#ifdef __riscv
#include "../accellib.h"
#endif

using namespace std;


int main() {

    GOOGLE_PROTOBUF_VERIFY_VERSION;

"""
    return header

def cpp_test_contents(fieldtypeobj):
    contents = """

        std::cout << "s1\\n" << std::flush;

        #ifdef __riscv
        string hostplat = "riscv";

        volatile char ** serializeoutputs = AccelSetupSerializer();

        #else
        string hostplat = "x86";
        #endif


        std::cout << "s2\\n" << std::flush;

        {TESTVALSINIT}


        std::cout << "s3\\n" << std::flush;

            std::cout << "s4\\n" << std::flush;

            google::protobuf::Arena arena;

            {ALLOCFIELDMESSAGE}

{SETTERLINES}

            #define SERITERS 1000

            primitivetests::{MESSAGENAME}* parseintos[SERITERS];

            for (int q = 0; q < SERITERS; q++) {{
               parseintos[q] = google::protobuf::Arena::CreateMessage<primitivetests::{MESSAGENAME}>(&arena);

               fillmessage = parseintos[q];
{SETTERLINES}
            }}

            string outstr;
            fillmessage->SerializeToString(&outstr);
            char * cpuserialized = (char*)outstr.c_str();
            int cpuserialized_len = outstr.length();

            std::cout << "CPU: SERIALIZEDLENGTH: " << cpuserialized_len << "\\n" << std::flush;
            for (int l = 0; l < cpuserialized_len; l++) {{
                printf("cpubyte: %02x\\n", cpuserialized[l]);
            }}

            uint64_t singleserializedlen = outstr.length();
            std::cout << "encodedlen " << singleserializedlen << "\\n" << std::flush;
            uint64_t total_bytes_processed = singleserializedlen * SERITERS;


#ifdef __riscv
            std::cout << "s5\\n" << std::flush;

            auto t1 = std::chrono::steady_clock::now();
            for (int iterser = 0; iterser < SERITERS; iterser++) {{
                AccelSerializeToString(primitivetests, {MESSAGENAME}, parseintos[iterser]);
            }}

            volatile char * serres = BlockOnSerializedValue(serializeoutputs, SERITERS-1);
            size_t serlen = GetSerializedLength(serializeoutputs, SERITERS-1);

            auto t2 = std::chrono::steady_clock::now();
            auto duration1 = std::chrono::duration_cast<std::chrono::microseconds>(t2 - t1).count();
            std::cout << (duration1 / (SERITERS * 1.0)) << ", us per iter, " << hostplat << "-accel, {TESTNAME}, " << testvals[0] << "\\n" << std::flush;
            std::cout << (((double)(total_bytes_processed)) / (((double)duration1) / 1000000.0)  / 1000000000.0 * 8) << ", Gbits/s, " << hostplat << "-accel, {TESTNAME}, " << testvals[0] << "\\n" << std::flush;

            std::cout << "ACCEL: SERIALIZEDLENGTH: " << serlen << ", SERPTR: " << ((uint64_t)serres) << "\\n" << std::flush;
            for (int l = 0; l < serlen; l++) {{
                printf("accelbyte: %02x\\n", serres[l]);
            }}


            for (int iterser = 0; iterser < SERITERS; iterser++) {{
                volatile char * serres = BlockOnSerializedValue(serializeoutputs, iterser);
                size_t serlen = GetSerializedLength(serializeoutputs, iterser);

                if (serlen != cpuserialized_len) {{
                    printf("FAIL MISMATCHED LEN\\n");
                    exit(1);
                }}
                for (int l = 0; l < serlen; l++) {{
                    if (cpuserialized[l] != serres[l]) {{
                        printf("FAIL MISMATCHED VALUE\\n");
                    }}
                }}
            }}

#endif

            std::cout << "s6\\n" << std::flush;

            primitivetests::{MESSAGENAME}* parseintoscpu[SERITERS];

            char * seroutputscpu[SERITERS];
            for (int out = 0; out < SERITERS; out++) {{
                seroutputscpu[out] = (char*)malloc(singleserializedlen*2);
            }}

            for (int q = 0; q < SERITERS; q++) {{
               parseintoscpu[q] = google::protobuf::Arena::CreateMessage<primitivetests::{MESSAGENAME}>(&arena);
               fillmessage = parseintoscpu[q];
{SETTERLINES}
            }}

            auto t3 = std::chrono::steady_clock::now();
            for (int i = 0; i < SERITERS; i++) {{
                parseintoscpu[i]->SerializeToArray(seroutputscpu[i], singleserializedlen*2);
            }}

            auto t4 = std::chrono::steady_clock::now();
            auto duration2 = std::chrono::duration_cast<std::chrono::microseconds>(t4 - t3).count();

            std::cout << (duration2 / (SERITERS * 1.0)) << ", us per iter, " << hostplat << ", {TESTNAME}, " << testvals[0] << "\\n" << std::flush;

            std::cout << (((double)(total_bytes_processed)) / (((double)duration2) / 1000000.0)  / 1000000000.0 * 8) << ", Gbits/s, " << hostplat << ", {TESTNAME}, " << testvals[0] << "\\n" << std::flush;

            char * serrescpu = seroutputscpu[SERITERS-1];
            size_t serlencpu = singleserializedlen;

            if (serlencpu != cpuserialized_len) {{
                printf("FAIL MISMATCHED LEN\\n");
                exit(1);
            }}
            for (int l = 0; l < serlencpu; l++) {{
                if (cpuserialized[l] != serrescpu[l]) {{
                    printf("FAIL MISMATCHED VALUE\\n");
                }}
            }}

        std::cout << "s7\\n" << std::flush;

        google::protobuf::ShutdownProtobufLibrary();
        return 0;
}}
""".format(FIELDTYPE=fieldtypeobj.get_fieldname_single_ident(), TESTVALSINIT=fieldtypeobj.generate_testvals_initialized(), SETTERLINES=fieldtypeobj.get_setter_lines(), ALLOCFIELDMESSAGE=fieldtypeobj.get_allocator_line(), TESTNAME=fieldtypeobj.get_test_name(), MESSAGENAME=fieldtypeobj.get_message_name())
    return contents

def build_cpp_tests(alltestobjs):
    for testobj in alltestobjs:
        contents = cpp_file_header()
        contents += cpp_test_contents(testobj)

        writeme = open(base_output_dir + testobj.get_test_name() + ".cpp", "w")
        writeme.write(contents)
        writeme.close()


def build_testsfrag(alltestobjs):
    rvtests = "rvtests = \\\n"
    x86tests = "x86tests = \\\n"

    for testobj in alltestobjs:
        rvtests += base_output_dir + testobj.get_test_name() +  ".riscv \\\n"
        x86tests += base_output_dir + testobj.get_test_name() + ".x86 \\\n"
    rvtests = rvtests[:-3]
    x86tests = x86tests[:-3]

    protocc = "protocc = " + protoout_cc
    protoh = "protoh = " + protoout_h
    protoboth = "protos = " + protoout_cc + " " + protoout_h

    contents = rvtests + "\n" + x86tests + "\n" + protocc + "\n" + protoh + "\n" + protoboth + "\n"

    writeme = open(testsfragout, "w")
    writeme.write(contents)
    writeme.close()



if __name__ == '__main__':
    base_output_dir = "primitive-tests-serializer/"
    protoout = base_output_dir + "primitives.proto"
    protoout_cc = base_output_dir + "primitives.pb.cc"
    protoout_h = base_output_dir + "primitives.pb.h"
    testsfragout= "testsfrag-serializer.mk"

    types_and_default_vals = dict()

    alltests = sorted(primitive_info_objs, key=lambda x: x.get_test_name())
    alltest_names = list(map(lambda x: x.get_test_name(), alltests))

    print("generating:")
    print(alltest_names)

    build_proto_file(alltests)
    build_cpp_tests(alltests)
    build_testsfrag(alltests)

