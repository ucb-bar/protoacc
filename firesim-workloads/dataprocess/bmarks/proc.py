import collections

results = collections.defaultdict(list)
totalbytes = collections.defaultdict(list)

a = open('deserresults', 'r')
b = a.readlines()
a.close()

currentbench = ""

for lin in b:
    lin = lin.strip()
    if "bench" in lin:
        currentbench = lin
    if "us des" in lin:
        results[currentbench + "-des-accel"].append(float(lin.split()[0]))
    if "b, " in lin:
        v = float(lin.split(",")[1].strip())
        totalbytes[currentbench + "-des-accel"].append(v)


a = open('serresults', 'r')
b = a.readlines()
a.close()

currentbench = ""

for lin in b:
    lin = lin.strip()
    if "bench" in lin:
        currentbench = lin
    if "us ser" in lin:
        results[currentbench + "-ser-accel"].append(float(lin.split()[0]))
    if "b, " in lin:
        v = float(lin.split(",")[1].strip())
        totalbytes[currentbench + "-ser-accel"].append(v)

a = open('plainresults', 'r')
b = a.readlines()
a.close()

currentbench = ""

for lin in b:
    lin = lin.strip()
    if "bench" in lin:
        currentbench = lin
    if "us des" in lin:
        results[currentbench + "-des-boomonly"].append(float(lin.split()[0]))
    if "us ser" in lin:
        results[currentbench + "-ser-boomonly"].append(float(lin.split()[0]))
    if "b, " in lin:
        v = float(lin.split(",")[1].strip())
        totalbytes[currentbench + "-des-boomonly"].append(v)
        totalbytes[currentbench + "-ser-boomonly"].append(v)


a = open('x86results', 'r')
b = a.readlines()
a.close()

currentbench = ""

for lin in b:
    if "running bench" in lin:
        currentbench = lin.split()[-1].replace(".x86", "")
    if "us des" in lin:
        results[currentbench + "-des-xeon"].append(float(lin.split()[0]))
    if "us ser" in lin:
        results[currentbench + "-ser-xeon"].append(float(lin.split()[0]))
    if "b, " in lin:
        v = float(lin.split(",")[1].strip())
        totalbytes[currentbench + "-des-xeon"].append(v)
        totalbytes[currentbench + "-ser-xeon"].append(v)


reskeysordered = sorted(set(map(lambda x: x.split("-")[0], results.keys())))
print(reskeysordered)


def process_bench_op(bench, op, system):
    num_iters = len(results[bench + "-" + op + "-" + system]) * 1.0
    vals_avg = sum(results[bench + "-" + op + "-" + system]) / num_iters
    bytes_avg = sum(totalbytes[bench + "-" + op + "-" + system]) / num_iters

    gbits_avg = bytes_avg * 8.0 / 1000000000.0

    avg_us = vals_avg
    avg_s = avg_us / 1000000.0
    avg_gbps = gbits_avg / avg_s

    return [avg_us, avg_gbps]


for op in ["ser", "des"]:
    for system in ["accel", "boomonly", "xeon"]:
        for bench in reskeysordered:
            r = process_bench_op(bench, op, system)
            print(bench + "-" + op + "-" + system + ": " + str(r))

        print("---------")

