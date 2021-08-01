import numpy as np
import matplotlib
# don't use xwindow
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from scipy.stats.mstats import gmean

accelout = "FINAL_PROCESSED"

a = open(accelout, 'r')
acceldat = a.readlines()
a.close()

acceldat_arrays_ser = []
acceldat_arrays_des = []

boomdat_arrays_ser = []
boomdat_arrays_des = []

x86dat_arrays_ser = []
x86dat_arrays_des = []

def process_bench_line(lin):
    dat = lin.split(": ")[-1]
    dat = dat.replace("[", "").replace("]", "")
    dat = dat.split(", ")
    return list(map(float, dat))


for lin in acceldat:
    if "bench" in lin:
        if "ser-accel" in lin:
            acceldat_arrays_ser.append(process_bench_line(lin))
        if "des-accel" in lin:
            acceldat_arrays_des.append(process_bench_line(lin))
        if "ser-boomonly" in lin:
            boomdat_arrays_ser.append(process_bench_line(lin))
        if "des-boomonly" in lin:
            boomdat_arrays_des.append(process_bench_line(lin))
        if "ser-xeon" in lin:
            x86dat_arrays_ser.append(process_bench_line(lin))
        if "des-xeon" in lin:
            x86dat_arrays_des.append(process_bench_line(lin))

print(acceldat_arrays_ser)
print(acceldat_arrays_des)

print(boomdat_arrays_ser)
print(boomdat_arrays_des)

print(x86dat_arrays_ser)
print(x86dat_arrays_des)


accel_ser_speedup = [acceldat_arrays_ser[x][1] / x86dat_arrays_ser[x][1] for x in range(len(acceldat_arrays_ser))]
accel_des_speedup = [acceldat_arrays_des[x][1] / x86dat_arrays_des[x][1] for x in range(len(acceldat_arrays_des))]

accel_boom_ser_speedup = [acceldat_arrays_ser[x][1] / boomdat_arrays_ser[x][1] for x in range(len(acceldat_arrays_ser))]
accel_boom_des_speedup = [acceldat_arrays_des[x][1] / boomdat_arrays_des[x][1] for x in range(len(acceldat_arrays_des))]

print("xeon speedup")
print(accel_ser_speedup)
print(accel_des_speedup)
print("boom speedup")
print(accel_boom_ser_speedup)
print(accel_boom_des_speedup)


def geomean_array(arr):
    l = len(arr) * 1.0
    prod = 1.0
    for x in arr:
        prod *= x
    return prod ** (1.0/l)


print("ser geom speedup vs xeon: " + str(geomean_array(accel_ser_speedup)))
print("des geom speedup vs xeon: " + str(geomean_array(accel_des_speedup)))

print("ser geom speedup vs boom: " + str(geomean_array(accel_boom_ser_speedup)))
print("des geom speedup vs boom: " + str(geomean_array(accel_boom_des_speedup)))



def do_plot(title, filename, boom, accel, xeon):
    fig, ax = plt.subplots()

    bar_width = 0.15

    def get_gbps_and_add_gmean(arr):
        l = list(map(lambda x: x[1], arr))
        l.append(gmean(l))
        return l

    boom = get_gbps_and_add_gmean(boom)
    accel = get_gbps_and_add_gmean(accel)
    xeon = get_gbps_and_add_gmean(xeon)

    r1 = np.arange(len(boom))
    r2 = [x + bar_width for x in r1]
    r3 = [x + bar_width for x in r2]

    def do_plt(rnum, name, dat):
        print(dat)
        print(len(dat))
        plt.bar(rnum, dat, width=bar_width, label=name)


    do_plt(r1, 'riscv-boom', boom)
    do_plt(r2, 'Xeon', xeon)
    do_plt(r3, 'riscv-boom-accel', accel)

    plt.xlabel('Benchmark', fontweight='bold')
    plt.ylabel('Gbits/s', fontweight='bold')

    plt.title(title)

    labels = ["bench" + str(x) for x in range(6)] + ["geomean"]
    plt.xticks(list(r2), labels)
    plt.legend()

    fig = plt.gcf()
    fig.set_size_inches(12, 4)

    fig.savefig(filename + "pdf", format="pdf")
    fig.savefig(filename + "png", format="png")



do_plot("HyperProtoBench Deserialization Performance", "hyper-des.", boomdat_arrays_des,
        acceldat_arrays_des, x86dat_arrays_des)

do_plot("HyperProtoBench Serialization Performance", "hyper-ser.", boomdat_arrays_ser,
        acceldat_arrays_ser, x86dat_arrays_ser)





