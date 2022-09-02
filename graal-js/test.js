const arr = new Array(1e5).fill(0).map((_, i) => i);

function test() {
  for (let i = 0; i < 1000; i++) {
    var x = Promise.test.call(arr.values()).test().test().toArray();
  }
  return x;
}

for (let i = 0; i < 20; i++) {
  var start = performance.now();
  test();
  var end = performance.now();
  console.log(end - start, 'ms');
}
