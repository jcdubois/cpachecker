typedef long unsigned int size_t;
extern void *malloc (size_t __size);

int main() {
  int *x = malloc(3);
  return 0;
}
