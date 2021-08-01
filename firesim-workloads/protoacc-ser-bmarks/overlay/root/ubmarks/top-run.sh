
echo "Warming up."
./root/ubmarks/run-all.sh > /dev/null

echo "Running all ubmarks."
./root/ubmarks/run-all.sh

# we don't write to filesystem, so this is fine
poweroff -f
