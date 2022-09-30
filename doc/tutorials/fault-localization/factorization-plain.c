int __VERIFIER_nondet_int();
void reach_error();

int isPrime(int n){
  for (int j = 2; j <= n/2; j++) {
    if(n % j == 0) return 0;	
  }
  return 1;
}

int main(){
  int num = __VERIFIER_nondet_int();
  
  if (num < 1) return 0;
  
  for (int i = 2; i <= num; i++) {
    if (num % i == 0 && isPrime(i)) {
      num = num / (i + 1); // should be num = num / i instead
      i--;
    }
  }
  
  if(num != 1) {
    reach_error();
  }
}
