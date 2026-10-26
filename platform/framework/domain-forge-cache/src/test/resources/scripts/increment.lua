return redis.call('INCRBY', KEYS[1], ARGV[1])
