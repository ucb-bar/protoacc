from collections import defaultdict
import numpy as np
import matplotlib
# don't use xwindow
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.stats.mstats import gmean

all_results = defaultdict(lambda: defaultdict(list))


accel_GHz = 2.0

def readfile(fname):
    q = open(fname, "r")
    z = q.readlines()
    q.close()

    for line in z:
        if "Gbits/s" in line:
            lsplit = line.strip().split(",")
            val = float(lsplit[0].strip())
            h = lsplit[2].strip()
            host = ""
            if h == "x86":
                host = "Xeon 2.3 GHz"
            elif h == "riscv":
                host = fname + " " + str(accel_GHz) + " GHz"
            elif h == "riscv-accel":
                host = fname + "-accel " + str(accel_GHz) + " GHz"

            dtype = lsplit[3].strip()

            all_results[dtype][host].append(val)



readfile("x86")
readfile("riscv-boom")

print(all_results)


def split_varint_data():
    uints = dict()
    uints_repeated = dict()
    for k in all_results.keys():
        if "uint64_size" in k:
            if "repeated" in k:
                k2 = int(k.split("size")[-1].replace("B", "").replace("_repeated", ""))
                uints_repeated[k2] = all_results[k]
            elif "fields" in k:
                continue
            else:
                k2 = int(k.split("size")[-1].replace("B", ""))
                uints[k2] = all_results[k]

    print(uints)
    print(uints_repeated)

    uints_collapsed = defaultdict(list)
    uints_repeated_collapsed = defaultdict(list)
    for x in range(0, 11):
        for k in uints[x].keys():
            uints_collapsed[k].append(uints[x][k][0])
        for k in uints_repeated[x].keys():
            uints_repeated_collapsed[k].append(uints_repeated[x][k][0])

    print(uints_collapsed)
    print(uints_repeated_collapsed)

    return uints_collapsed, uints_repeated_collapsed


def plot_varints(dat, repeated=False):
    fig, ax = plt.subplots()


    bar_width = 0.15

    gmeans_only = dict()
    for k in dat.keys():
        gm = gmean(dat[k])
        gmeans_only[k] = gm
        dat[k] = dat[k] + [gm]

    r1 = np.arange(len(dat['riscv-boom-accel 2.0 GHz']))
    r2 = [x + bar_width for x in r1]
    r3 = [x + bar_width for x in r2]

    def do_plt(rnum, name):
        print(dat[name])
        print(len(dat[name]))
        plt.bar(rnum, dat[name], width=bar_width, label=name)

    do_plt(r1, 'riscv-boom 2.0 GHz')
    do_plt(r2, 'riscv-boom-accel 2.0 GHz')
    do_plt(r3, 'Xeon 2.3 GHz')
    plt.xlabel('Fieldtype, Encoded Fieldwidth (Bytes)', fontweight='bold')
    plt.ylabel("Gbits/s", fontweight='bold')
    titlename = "Repeated " if repeated else ""
    plt.title("Protobuf Primitive " + titlename + "Message Deserialization Performance")

    labels = ["varint-" + str(size) for size in range(0, len(r1))]
    labels[-1] = "geomean"

    plt.xticks(list(r2), labels)
    plt.legend()


    fig = plt.gcf()
    fig.set_size_inches(12, 4)
    filename = ""
    if repeated:
        filename = "varintsRepeated.pdf"
    else:
        filename = "varints.pdf"
    fig.savefig(filename, format="pdf")
    fig.savefig(filename.replace("pdf", "png"), format="png")


    print("------------------------------------------------------")
    print("""for {}""".format(filename))

    for k in gmeans_only.keys():
        print("""{}, {}""".format(k, gmeans_only[k]))

    boom = gmeans_only['riscv-boom 2.0 GHz']
    xeon = gmeans_only['Xeon 2.3 GHz']
    boom_accel = gmeans_only['riscv-boom-accel 2.0 GHz']

    def normalize(dat, baseline):
        return round((dat / baseline), 2)

    print("""BOOM-accel vs. BOOM: {}x faster""".format(normalize(boom_accel, boom)))
    print("""BOOM-accel vs. Xeon: {}x faster""".format(normalize(boom_accel, xeon)))

nonrepeat, repeat = split_varint_data()

def plot_others_combined(varintdat, varintsrepeated, types_wanted, outputfilename):
    fig, ax = plt.subplots()

    dat = dict()

    for k in all_results[types_wanted[0]].keys():
        dat[k] = []

    for t in types_wanted:
        trow = all_results[t]
        for host in trow.keys():
            dat[host].append(trow[host][0])

    for vhost in varintdat.keys():
        dat[vhost] = varintdat[vhost] + dat[vhost]

    numvarints = len(varintdat[varintdat.keys()[0]])
    varint_labels = []
    for x in range(0, numvarints):
        namebase = "varint-" + str(x)
        if varintsrepeated:
            namebase += "-R"
        varint_labels.append(namebase)

    def replacements(inputstr):
        inputstr = inputstr.replace("Pacc", "")
        inputstr = inputstr.replace("Message", "-SUB")
        inputstr = inputstr.replace("_repeated", "-R")
        return inputstr

    types_wanted = [replacements(x) for x in types_wanted]

    types_wanted = varint_labels + types_wanted

    print(types_wanted)
    print(dat)

    bar_width = 0.30

    gmeans_only = dict()
    for k in dat.keys():
        gm = gmean(dat[k])
        gmeans_only[k] = gm
        dat[k] = dat[k] + [gm]

    r1 = np.arange(len(dat['riscv-boom-accel 2.0 GHz']))
    r2 = [x + bar_width for x in r1]
    r3 = [x + bar_width for x in r2]

    def do_plt(rnum, name):
        displayname=name.split(" ")[0]
        plt.bar(rnum, dat[name], width=bar_width, label=displayname)

    do_plt(r1, 'riscv-boom 2.0 GHz')
    do_plt(r2, 'Xeon 2.3 GHz')
    do_plt(r3, 'riscv-boom-accel 2.0 GHz')
    plt.xlabel('Fieldtype', fontweight='bold')
    plt.xticks(rotation=40, ha='right')
    plt.ylabel("Gbits/s", fontweight='bold')
    plt.title("Protobuf microbenchmark: deserialization performance")
    plt.tight_layout()

    labels = types_wanted + ["geomean"]

    plt.xticks(list(r2), labels)
    plt.legend()


    fig = plt.gcf()
    fig.set_size_inches(12, 3)
    fig.savefig(outputfilename, format="pdf", bbox_inches='tight')
    fig.savefig(outputfilename.replace("pdf", "png"), format="png", bbox_inches='tight')

    print("------------------------------------------------------")
    print("""for {}""".format(outputfilename))


    for k in gmeans_only.keys():
        print("""{}, {}""".format(k, gmeans_only[k]))

    boom = gmeans_only['riscv-boom 2.0 GHz']
    xeon = gmeans_only['Xeon 2.3 GHz']
    boom_accel = gmeans_only['riscv-boom-accel 2.0 GHz']

    def normalize(dat, baseline):
        return round((dat / baseline), 2)

    print("""BOOM-accel vs. BOOM: {}x faster""".format(normalize(boom_accel, boom)))
    print("""BOOM-accel vs. Xeon: {}x faster""".format(normalize(boom_accel, xeon)))
    print("------------------------------------------------------")

types_wanted1 = ['double', 'float']

types_wanted2 = ['string',
                 'string_15',
                 'string_long',
                 'string_very_long',
                'double_repeated', 'float_repeated', 'PaccboolMessage',
                'PaccdoubleMessage', 'PaccstringMessage']



plot_others_combined(nonrepeat, False, types_wanted1, "nonalloc.pdf")
plot_others_combined(repeat, True, types_wanted2, "allocd.pdf")



